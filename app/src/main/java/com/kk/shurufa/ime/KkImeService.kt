package com.kk.shurufa.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyboardLayout
import com.kk.shurufa.keyboard.KeyboardListener
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.MathLayout
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PopupOverlay
import com.kk.shurufa.keyboard.SymbolCategory
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import com.kk.shurufa.settings.SettingsActivity
import java.io.File
import kotlin.math.abs

class KkImeService : InputMethodService(), KeyboardListener, CandidateBar.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var repo: LayoutRepo
    private lateinit var theme: KbTheme

    private lateinit var root: FrameLayout
    private lateinit var bar: CandidateBar
    private lateinit var content: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var overlay: PopupOverlay
    private lateinit var candGrid: GridPanel
    private lateinit var symbolPanel: LinearLayout
    private lateinit var symRail: GridPanel
    private lateinit var symCells: GridPanel
    private lateinit var symBottom: KeyboardView
    private lateinit var clipPanel: LinearLayout
    private lateinit var clipList: GridPanel

    enum class Panel { LETTERS, SYMBOLS, MATH, PHONE, CURSOR, CLIPBOARD, CANDIDATES }

    private var panel = Panel.LETTERS
    private var lang = "zh"
    private var tempLang: String? = null
    private var shift = 0
    private var autoShift = false
    private var lastShiftTap = 0L
    private var lastSpaceAt = 0L

    private val layouts = HashMap<String, KeyboardLayout>()
    private var math: MathLayout? = null
    private var mathTab = "basic"
    private var symbols: List<SymbolCategory> = emptyList()
    private var commonZh: List<Output> = emptyList()
    private var commonLatin: List<Output> = emptyList()
    private var symCat = "common"
    private var symLock = false
    private var symItems: List<Output> = emptyList()
    private var recentSymbols = mutableListOf<String>()

    private var selStart = 0
    private var selEnd = 0
    private var selectMode = false
    private var bsAnchor = -1

    private lateinit var clips: ClipHistory
    private var clipItems: List<ClipHistory.Entry> = emptyList()

    private val rimeListener: (RimeEngine.State) -> Unit = { onRimeState(it) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = LayoutRepo(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        recentSymbols = prefs.sp.getString("sym_recent", "")!!.split('\u0001').filter { it.isNotEmpty() }.toMutableList()
        clips = ClipHistory(File(filesDir, "clipboard.txt"))
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        RimeEngine.addListener(rimeListener)
        RimeEngine.start(this)
        lang = prefs.languages.firstOrNull() ?: "zh"
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        RimeEngine.removeListener(rimeListener)
        super.onDestroy()
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        val sensitive = Build.VERSION.SDK_INT >= 33 &&
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        if (sensitive) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
        clips.add(text)
        if (panel == Panel.CLIPBOARD) refreshClips()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "sym_recent" || key?.startsWith("app_lang_") == true) return
        layouts.clear(); math = null
        if (key == Prefs.ZH_EMOJI || key == Prefs.ZH_TRAD) applyRimeOptions()
        if (::root.isInitialized) setInputView(onCreateInputView())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::root.isInitialized) setInputView(onCreateInputView())
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        theme = KbTheme.resolve(this, prefs)
        loadData()
        root = FrameLayout(this)
        root.setBackgroundColor(theme.background)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = CandidateBar(this, prefs).also { it.theme = theme; it.listener = this }
        content = FrameLayout(this)
        overlay = PopupOverlay(this, prefs).also { it.theme = theme }
        keyboard = KeyboardView(this, prefs).also { it.theme = theme; it.listener = this; it.overlay = overlay }
        candGrid = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; columns = 0; textSp = 19f; rowHeightDp = 46f
            onItem = { i -> onCandidate(i) }
        }
        buildSymbolPanel()
        buildClipPanel()
        content.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (prefs.toolbarVisible || true) column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bottom)
            ins
        }
        showPanel(panel)
        return root
    }

    private fun loadData() {
        try {
            math = repo.math()
            symbols = repo.symbols()
            repo.symbolCommons().let { commonZh = it.first; commonLatin = it.second }
        } catch (e: Exception) {
            Toast.makeText(this, "布局文件有错误：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun layout(id: String): KeyboardLayout = layouts.getOrPut(id) {
        try { repo.keyboard(id) } catch (e: Exception) {
            Toast.makeText(this, "布局 $id 读取失败：${e.message}", Toast.LENGTH_LONG).show()
            KeyboardLayout(id, id, "direct", emptyList())
        }
    }

    private val curLang get() = tempLang ?: lang
    private val isZh get() = layout(curLang).engine == "rime"
    private val composing get() = isZh && RimeEngine.ready && RimeBridge.input().isNotEmpty()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        tempLang = null
        selectMode = false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (prefs.perAppLanguage && !restarting) prefs.appLanguage(info.packageName ?: "")?.takeIf { it in prefs.languages }?.let { lang = it }
        val isPassword = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val isAddress = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        if ((isPassword || isAddress) && isZh) tempLang = prefs.languages.firstOrNull { layout(it).engine != "rime" } ?: "en"
        panel = when {
            cls == InputType.TYPE_CLASS_PHONE -> Panel.PHONE
            (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) && prefs.numberFieldMath -> { mathTab = "basic"; Panel.MATH }
            else -> Panel.LETTERS
        }
        shift = 0
        if (::root.isInitialized) showPanel(panel)
        updateAutoCap()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (RimeEngine.ready) { RimeBridge.clearComposition() }
        currentInputConnection?.finishComposingText()
        if (::bar.isInitialized) syncBar()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
    }

    private fun onRimeState(s: RimeEngine.State) {
        if (s == RimeEngine.State.READY) applyRimeOptions()
        if (::bar.isInitialized) syncBar()
    }

    private fun applyRimeOptions() {
        if (!RimeEngine.ready) return
        RimeBridge.setOption("emoji", prefs.zhEmoji)
        RimeBridge.setOption("traditionalization", prefs.zhTraditional)
    }

    // ---- panels -------------------------------------------------------------------------

    private fun lettersRows(): List<List<Key>> {
        val base = layout(curLang).rows
        if (!prefs.numberRow || base.isEmpty()) return base
        val digits = (1..10).map { Key(main = Output.of((it % 10).toString())) }
        return listOf(digits) + base
    }

    private fun kbHeight(rows: Int): Int {
        val keyH = dp(prefs.keyHeightDp.toFloat())
        return (dp(6f) + dp(prefs.bottomPaddingDp + 2f) + rows * keyH + (rows - 1) * dp(prefs.keyGapDp)).toInt()
    }

    private fun showPanel(p: Panel) {
        panel = p
        content.removeAllViews()
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val fixed = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kbHeight(lettersRows().size.coerceAtLeast(4)))
        when (p) {
            Panel.LETTERS -> { keyboard.setRows(lettersRows()); content.addView(keyboard, lp) }
            Panel.PHONE -> { keyboard.setRows(layout("phone").rows); content.addView(keyboard, lp) }
            Panel.CURSOR -> { keyboard.setRows(layout("cursor").rows); content.addView(keyboard, lp) }
            Panel.MATH -> {
                val m = math
                if (m == null) { showPanel(Panel.LETTERS); return }
                val tab = m.tabs.firstOrNull { it.id == mathTab } ?: m.tabs.first()
                keyboard.setRows(tab.rows + listOf(m.bottom), math = true)
                content.addView(keyboard, lp)
            }
            Panel.SYMBOLS -> { refreshSymbols(); content.addView(symbolPanel, fixed); animateIn(symbolPanel) }
            Panel.CLIPBOARD -> { refreshClips(); content.addView(clipPanel, fixed); animateIn(clipPanel) }
            Panel.CANDIDATES -> {
                candGrid.setItems(RimeBridge.candidates(240).toList())
                content.addView(candGrid, fixed)
            }
        }
        bar.expanded = p == Panel.CANDIDATES
        syncBar()
    }

    private fun animateIn(v: View) {
        if (!prefs.animations) return
        v.alpha = 0f; v.translationY = dp(10f)
        v.animate().alpha(1f).translationY(0f).setDuration((170 * prefs.animSpeed).toLong()).start()
    }

    private fun syncBar() {
        if (!::bar.isInitialized) return
        when {
            panel == Panel.MATH -> math?.let { m -> bar.showMath(m.tabs.map { it.id to it.name }, mathTab, prefs.mathFormat) }
            isZh && RimeEngine.state == RimeEngine.State.DEPLOYING && panel == Panel.LETTERS -> bar.showMessage("首次部署雾凇拼音词库，约需一分钟…")
            isZh && RimeEngine.state == RimeEngine.State.PREPARING && panel == Panel.LETTERS -> bar.showMessage("正在准备中文词库…")
            isZh && RimeEngine.state == RimeEngine.State.FAILED -> bar.showMessage("中文引擎启动失败：${RimeEngine.error ?: "未知错误"}")
            composing -> {
                val cands = RimeBridge.candidates(80).toList()
                bar.showCandidates(RimeBridge.preedit(), cands, emptyList())
                if (prefs.zhPreeditInline) currentInputConnection?.setComposingText(RimeBridge.input(), 1)
            }
            else -> {
                bar.showToolbar()
                if (panel == Panel.CANDIDATES) showPanel(Panel.LETTERS)
            }
        }
    }

    private fun buildSymbolPanel() {
        symbolPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        symRail = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; columns = 1; rowHeightDp = 38f; textSp = 14f; cellStyle = GridPanel.CellStyle.RAIL
            onItem = { i -> symCat = symbols[i].id; refreshSymbols() }
        }
        symCells = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme
            onItem = { i -> symItems.getOrNull(i)?.let { onSymbol(it) } }
        }
        top.addView(symRail, LinearLayout.LayoutParams(dp(74f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(symCells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        symBottom = KeyboardView(this, prefs).also { it.theme = theme; it.listener = this; it.overlay = overlay }
        symBottom.setRows(listOf(listOf(
            Key(fn = "abc", label = "返回", width = 1.4f), Key(fn = "math", label = "数学", width = 1.2f),
            Key(fn = "lock"), Key(fn = "space", width = 3f), Key(fn = "backspace", width = 1.2f), Key(fn = "enter", width = 1.2f),
        )))
        symbolPanel.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        symbolPanel.addView(symBottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshSymbols() {
        if (symbols.isEmpty()) return
        val idx = symbols.indexOfFirst { it.id == symCat }.coerceAtLeast(0)
        val cat = symbols[idx]
        symItems = if (cat.id == "common") {
            val base = if (isZh) commonZh else commonLatin
            (recentSymbols.map { Output.of(it) } + base).distinctBy { it.text }
        } else cat.items
        symRail.selectedIndex = idx
        symRail.setItems(symbols.map { it.name }, resetScroll = false)
        symCells.columns = if (cat.wide) 2 else 5
        symCells.textSp = if (cat.wide) 15f else 19f
        symCells.marks = if (cat.id == "common") recentSymbols.indices.toSet() else emptySet()
        symCells.setItems(symItems.map { it.label })
        symBottom.lockOn = symLock
        symBottom.invalidate()
    }

    private fun onSymbol(o: Output) {
        commitComposingFirst()
        commit(o.text)
        recentSymbols.remove(o.text)
        recentSymbols.add(0, o.text)
        while (recentSymbols.size > 12) recentSymbols.removeAt(recentSymbols.size - 1)
        prefs.sp.edit().putString("sym_recent", recentSymbols.joinToString("\u0001")).apply()
        if (!symLock && prefs.symbolAutoReturn) showPanel(Panel.LETTERS)
    }

    private fun buildClipPanel() {
        clipPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipList = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; columns = 1; rowHeightDp = 46f; textSp = 15f; cellStyle = GridPanel.CellStyle.ROW
            onItem = { i -> clipItems.getOrNull(i)?.let { commit(it.text, pairable = false) } }
            onItemLong = { i ->
                clipItems.getOrNull(i)?.let { clips.togglePin(it.text) }
                refreshClips()
                Toast.makeText(this@KkImeService, "已切换固定", Toast.LENGTH_SHORT).show()
            }
        }
        val bottom = KeyboardView(this, prefs).also { it.theme = theme; it.listener = this; it.overlay = overlay }
        bottom.setRows(listOf(listOf(
            Key(fn = "abc", label = "返回", width = 1.4f), Key(fn = "clear_clips", label = "清空未固定", width = 2f),
            Key(fn = "space", width = 2.4f), Key(fn = "backspace", width = 1.2f), Key(fn = "enter", width = 1.2f),
        )))
        clipPanel.addView(clipList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        clipPanel.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshClips() {
        clipItems = clips.entries()
        clipList.marks = clipItems.indices.filter { clipItems[it].pinned }.toSet()
        clipList.setItems(if (clipItems.isEmpty()) listOf("复制过的文字会出现在这里；长按一条可固定") else clipItems.map { it.text })
    }

    // ---- text editing -------------------------------------------------------------------

    private fun commitComposingFirst() {
        if (composing) {
            RimeBridge.commitComposition()
            flushRime()
        }
    }

    /** Sends a key to Rime and applies the result. Returns false when Rime did not take it. */
    private fun rimeKey(code: Int, mask: Int = 0): Boolean {
        if (!RimeEngine.ready) return false
        val handled = RimeBridge.processKey(code, mask)
        flushRime()
        return handled
    }

    private fun flushRime() {
        val commit = RimeBridge.commitText()
        if (commit.isNotEmpty()) commit(commit, pairable = false)
        else if (prefs.zhPreeditInline && RimeBridge.input().isEmpty()) currentInputConnection?.finishComposingText()
        syncBar()
        if (panel == Panel.CANDIDATES) {
            if (composing) candGrid.setItems(RimeBridge.candidates(240).toList()) else showPanel(Panel.LETTERS)
        }
    }

    private val pairs = mapOf(
        "(" to ")", "（" to "）", "[" to "]", "【" to "】", "{" to "}", "«" to "»", "“" to "”", "‘" to "’",
        "《" to "》", "〈" to "〉", "「" to "」", "『" to "』", "〔" to "〕", "„" to "“", "⟨" to "⟩",
    )
    private val closers = pairs.values.toSet()

    /** Commits text; `‸` marks where the cursor should land. Handles bracket pairing. */
    private fun commit(text: String, pairable: Boolean = true) {
        val ic = currentInputConnection ?: return
        var t = text
        if (pairable && prefs.autoPair) {
            val cps = t.codePointCount(0, t.length)
            if (cps == 1 && t in closers && ic.getTextAfterCursor(1, 0)?.toString() == t) { moveBy(1); return }
            if (cps == 1 && pairs.containsKey(t)) {
                val selected = ic.getSelectedText(0)?.toString().orEmpty()
                t = t + selected + "‸" + pairs[t]
            } else if (cps == 2) {
                val a = t.substring(0, t.offsetByCodePoints(0, 1))
                val b = t.substring(a.length)
                if (pairs[a] == b) t = "$a‸$b"
            }
        }
        val idx = t.indexOf('‸')
        val clean = t.replace("‸", "")
        ic.beginBatchEdit()
        ic.commitText(clean, 1)
        if (idx in 0 until clean.length) moveBy(-(clean.length - idx))
        ic.endBatchEdit()
    }

    private fun cursorPos(): Int {
        val ic = currentInputConnection ?: return selEnd
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        return if (et != null) et.startOffset + et.selectionEnd else selEnd
    }

    private fun moveBy(n: Int) {
        val ic = currentInputConnection ?: return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et != null) {
            val p = (et.startOffset + et.selectionEnd + n).coerceAtLeast(0)
            ic.setSelection(p, p)
        } else {
            val code = if (n < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(n)) { sendDownUpKeyEvents(code) }
        }
    }

    private fun sendKey(code: Int, meta: Int = 0) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) { ic.commitText("", 1); return }
        // delete an empty bracket pair together
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        val after = ic.getTextAfterCursor(1, 0)?.toString()
        if (prefs.autoPair && before != null && pairs[before] == after) { ic.deleteSurroundingText(1, 1); return }
        if (Build.VERSION.SDK_INT >= 24) ic.deleteSurroundingTextInCodePoints(1, 0) else ic.deleteSurroundingText(1, 0)
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
        else if (info?.inputType == InputType.TYPE_NULL) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        else ic.commitText("\n", 1)
    }

    private fun updateAutoCap() {
        if (!::keyboard.isInitialized) return
        val directLetters = panel == Panel.LETTERS && !isZh
        val caps = directLetters && prefs.autoCap && (currentInputConnection?.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) ?: 0) != 0
        if (shift == 2) return
        val want = if (caps) 1 else if (autoShift) 0 else shift
        if (want != shift || caps != autoShift) {
            shift = want; autoShift = caps
            keyboard.invalidate()
        }
    }

    // ---- keyboard listener --------------------------------------------------------------

    override fun shiftState() = shift
    override fun spaceLabel(): String = if (panel == Panel.LETTERS) layout(curLang).label else "空格"

    override fun onOutput(key: Key, output: Output) {
        if (panel == Panel.MATH) { mathOut(output); return }
        val t = output.text
        val isMain = output === key.main
        if (isZh && panel == Panel.LETTERS) {
            if (isMain && t.length == 1 && (t[0] in 'a'..'z' || (t == "'" && composing))) {
                if (rimeKey(t[0].code)) return
                if (!RimeEngine.ready) { commit(t); return }
            }
            if (isMain && (t == "，" || t == "。") && composing) {
                RimeBridge.commitComposition(); flushRime()
            }
            commitComposingFirst()
            commit(smartPunct(t))
            return
        }
        var out = t
        if (isMain && key.isLetter && shift > 0) out = t.uppercase()
        commit(out)
        if (isMain && key.isLetter && shift == 1) { shift = 0; autoShift = false; keyboard.invalidate() }
        updateAutoCap()
    }

    private fun smartPunct(t: String): String {
        if (!prefs.smartPunct) return t
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: return t
        if (before.isNotEmpty() && before[0].isDigit()) return when (t) { "。" -> "."; "：" -> ":"; else -> t }
        return t
    }

    override fun onFunction(key: Key) {
        val ic = currentInputConnection
        when (key.fn) {
            "backspace" -> if (composing) rimeKey(RimeBridge.XK_BackSpace) else { deleteBackward(); updateAutoCap() }
            "space" -> {
                if (composing) { rimeKey(RimeBridge.XK_space); return }
                val now = SystemClock.uptimeMillis()
                val before = ic?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (prefs.doubleSpacePeriod && !isZh && panel == Panel.LETTERS && now - lastSpaceAt < 600 &&
                    before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic?.deleteSurroundingText(1, 0); commit(". ")
                } else commit(" ")
                lastSpaceAt = now
                updateAutoCap()
            }
            "enter" -> if (composing) rimeKey(RimeBridge.XK_Return) else { performEnter(); updateAutoCap() }
            "shift" -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == 2 -> 0
                    shift == 1 && now - lastShiftTap < 350 -> 2
                    shift == 1 -> 0
                    else -> 1
                }
                autoShift = false
                lastShiftTap = now
                keyboard.invalidate()
            }
            "language" -> switchLanguage(1)
            "symbols" -> { commitComposingFirst(); symCat = "common"; showPanel(Panel.SYMBOLS) }
            "math" -> { commitComposingFirst(); showPanel(Panel.MATH) }
            "abc" -> { selectMode = false; showPanel(Panel.LETTERS) }
            "lock" -> { symLock = !symLock; symBottom.lockOn = symLock; symBottom.invalidate()
                Toast.makeText(this, if (symLock) "已锁定，可以连续输入符号" else "已解锁，点一个符号就回到字母键盘", Toast.LENGTH_SHORT).show() }
            "left" -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, shiftMeta())
            "right" -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, shiftMeta())
            "up" -> sendKey(KeyEvent.KEYCODE_DPAD_UP, shiftMeta())
            "down" -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, shiftMeta())
            "word_left" -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or shiftMeta())
            "word_right" -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON or shiftMeta())
            "home" -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, shiftMeta())
            "end" -> sendKey(KeyEvent.KEYCODE_MOVE_END, shiftMeta())
            "select" -> { selectMode = !selectMode; Toast.makeText(this, if (selectMode) "选择模式：移动光标会选中文字" else "已退出选择模式", Toast.LENGTH_SHORT).show() }
            "select_all" -> ic?.performContextMenuAction(android.R.id.selectAll)
            "copy" -> { ic?.performContextMenuAction(android.R.id.copy); selectMode = false }
            "cut" -> { ic?.performContextMenuAction(android.R.id.cut); selectMode = false }
            "paste" -> ic?.performContextMenuAction(android.R.id.paste)
            "next_slot" -> nextSlot()
            "clear_clips" -> { clips.clearUnpinned(); refreshClips() }
        }
    }

    private fun shiftMeta() = if (selectMode) KeyEvent.META_SHIFT_ON else 0

    override fun longOptions(key: Key): List<Output> {
        key.fn?.let { return fnOptions(it) }
        val m = key.main ?: return emptyList()
        val list = mutableListOf<Output>()
        if (panel == Panel.MATH) {
            listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT).forEach { d -> key.flicks[d]?.let { list += it } }
            if (list.isNotEmpty()) list += m
            return list
        }
        if (prefs.lpHints) listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT).forEach { d -> key.flicks[d]?.let { list += it } }
        if (prefs.lpDigits && panel == Panel.LETTERS) {
            val row0 = layout(curLang).rows.firstOrNull()
            val col = row0?.indexOf(key) ?: -1
            if (col in 0..9) list += Output.of(((col + 1) % 10).toString())
        }
        if (prefs.lpCase && key.isLetter) {
            val up = m.text.uppercase(); val low = m.text.lowercase()
            if (shift > 0) { list += Output.of(low); list += Output.of(up) } else { list += Output.of(up); list += Output.of(low) }
        }
        list += key.long
        return list.distinctBy { it.text }.takeIf { it.size > 1 || (it.size == 1 && it[0].text != m.text) } ?: emptyList()
    }

    private fun fnOptions(fn: String): List<Output> = when (fn) {
        "language" -> prefs.languages.map { Output(layout(it).label, "§lang:$it") } +
            listOf(Output("系统切换", "§picker"), Output("设置", "§settings"))
        "symbols" -> listOf(Output("符号", "§sym"), Output("数学", "§math"), Output("光标", "§cursor"), Output("剪贴板", "§clip"))
        "enter" -> listOf(Output("换行", "§newline"), Output(enterActionName(), "§action"))
        "shift" -> listOf(Output("大写锁定", "§caps"))
        else -> emptyList()
    }

    private fun enterActionName() = when ((currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_SEND -> "发送"
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_NEXT -> "下一项"
        EditorInfo.IME_ACTION_DONE -> "完成"
        else -> "回车"
    }

    override fun onOption(key: Key, option: Output) {
        val t = option.text
        if (!t.startsWith("§")) { onOutput(key, option); return }
        when {
            t.startsWith("§lang:") -> setLanguage(t.removePrefix("§lang:"))
            t == "§picker" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            t == "§settings" -> openSettings()
            t == "§sym" -> onFunction(Key(fn = "symbols"))
            t == "§math" -> onFunction(Key(fn = "math"))
            t == "§cursor" -> showPanel(Panel.CURSOR)
            t == "§clip" -> showPanel(Panel.CLIPBOARD)
            t == "§newline" -> commit("\n")
            t == "§action" -> performEnter()
            t == "§caps" -> { shift = 2; keyboard.invalidate() }
        }
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        val meta = shiftMeta()
        repeat(abs(dx)) { sendKey(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, meta) }
        repeat(abs(dy)) { sendKey(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, meta) }
        if (dx != 0 || dy != 0) feedback(light = true)
    }

    override fun onCursorMode(active: Boolean) {
        if (active) { commitComposingFirst(); feedback() }
    }

    override fun onSwitchLanguage(direction: Int): String? {
        switchLanguage(direction)
        return layout(curLang).label
    }

    private fun switchLanguage(direction: Int) {
        val langs = prefs.languages
        if (langs.size < 2) return
        commitComposingFirst()
        val i = langs.indexOf(curLang).coerceAtLeast(0)
        setLanguage(langs[(i + direction + langs.size) % langs.size])
    }

    private fun setLanguage(id: String) {
        commitComposingFirst()
        tempLang = null
        lang = id
        shift = 0; autoShift = false
        currentInputEditorInfo?.packageName?.let { if (prefs.perAppLanguage) prefs.setAppLanguage(it, id) }
        if (panel != Panel.LETTERS) showPanel(Panel.LETTERS) else { keyboard.setRows(lettersRows()); syncBar() }
        updateAutoCap()
    }

    override fun onBackspaceRepeat() {
        if (composing) rimeKey(RimeBridge.XK_BackSpace) else deleteBackward()
    }

    private var bsText = ""

    override fun onBackspaceSwipe(words: Int, commit: Boolean) {
        val ic = currentInputConnection ?: return
        if (composing) { if (commit && words > 0) { RimeBridge.clearComposition(); flushRime() }; return }
        if (bsAnchor < 0) {
            bsAnchor = cursorPos()
            bsText = ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
        }
        val end = bsAnchor
        when {
            commit -> {
                if (words > 0) ic.commitText("", 1)
                bsAnchor = -1
                updateAutoCap()
            }
            words == 0 -> { ic.setSelection(end, end); bsAnchor = -1 }
            else -> ic.setSelection((end - wordsBack(bsText, words)).coerceAtLeast(0), end)
        }
    }

    /** Length of the last [n] words at the end of [s]; CJK text counts two characters as a word. */
    private fun wordsBack(s: String, n: Int): Int {
        var i = s.length
        repeat(n) {
            while (i > 0 && s[i - 1].isWhitespace()) i--
            if (i > 0 && isCjk(s[i - 1])) {
                var k = 0
                while (i > 0 && isCjk(s[i - 1]) && k < 2) { i--; k++ }
            } else if (i > 0 && !s[i - 1].isLetterOrDigit()) i--
            else while (i > 0 && s[i - 1].isLetterOrDigit() && !isCjk(s[i - 1])) i--
        }
        return s.length - i
    }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    override fun onPress(key: Key) = feedback(sound = key.fn)

    private fun feedback(light: Boolean = false, sound: String? = null) {
        if (prefs.vibrate) {
            val vib = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            val ms = if (light) (prefs.vibrateMs / 2).coerceAtLeast(4) else prefs.vibrateMs
            val amp = if (light) (prefs.vibrateStrength / 2).coerceAtLeast(1) else prefs.vibrateStrength
            if (vib.hasAmplitudeControl()) vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), amp.coerceIn(1, 255)))
            else vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (prefs.sound && !light) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val fx = when (sound) {
                "backspace" -> AudioManager.FX_KEYPRESS_DELETE
                "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
                "enter" -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am.playSoundEffect(fx, prefs.soundVolume)
        }
    }

    // ---- math ---------------------------------------------------------------------------

    private fun mathOut(o: Output) {
        val text = if (prefs.mathFormat == "tex") o.tex else o.text
        commit(text, pairable = false)
        bar.showResult(null)
        if (o.label == "=" && prefs.mathCalc && prefs.mathFormat == "uni") {
            val before = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: return
            val line = before.substringAfterLast('\n').dropLast(1)
            val expr = line.substringAfterLast('=')
            Calc.eval(expr)?.let { bar.showResult(Calc.format(it)) }
        }
    }

    private fun nextSlot() {
        val ic = currentInputConnection ?: return
        val after = ic.getTextAfterCursor(400, 0)?.toString() ?: return
        val m = Regex("\\{\\}|\\(\\)|\\[\\]|, |; |& |\\\\\\\\ ").find(after)
        val off = if (m != null) m.range.first + (if (m.value.endsWith(" ")) m.value.length else 1)
        else after.indexOfFirst { it in ")]}|‖⟩" }.let { if (it >= 0) it + 1 else after.length }
        moveBy(off)
    }

    // ---- candidate bar listener ---------------------------------------------------------

    override fun onTool(id: String) {
        when (id) {
            "settings" -> openSettings()
            "cursor" -> showPanel(Panel.CURSOR)
            "clipboard" -> showPanel(Panel.CLIPBOARD)
            "emoji" -> { symCat = "kao"; showPanel(Panel.SYMBOLS); Toast.makeText(this, "emoji 面板在下一版，先放了颜文字", Toast.LENGTH_SHORT).show() }
            "ai", "translate" -> Toast.makeText(this, "AI 和翻译在阶段 2 接入", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCandidate(index: Int) {
        if (!RimeEngine.ready) return
        RimeBridge.selectCandidateGlobal(index)
        flushRime()
    }

    override fun onExpand() {
        showPanel(if (panel == Panel.CANDIDATES) Panel.LETTERS else Panel.CANDIDATES)
    }

    override fun onMathTab(id: String) { mathTab = id; showPanel(Panel.MATH) }
    override fun onMathFormat(format: String) { prefs.put(Prefs.MATH_FORMAT, format) }
    override fun onResultChip(text: String) { commit(text, pairable = false); bar.showResult(null) }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Recent clipboard texts, newest first; pinned entries stay when the rest is cleared. */
class ClipHistory(private val file: File) {
    data class Entry(val text: String, val pinned: Boolean)

    private val list = mutableListOf<Entry>()

    init {
        if (file.isFile) file.readText().split('\u0002').filter { it.length > 1 }.forEach {
            list += Entry(it.substring(1), it[0] == '1')
        }
    }

    private fun save() = file.writeText(list.joinToString("\u0002") { (if (it.pinned) "1" else "0") + it.text })

    fun add(text: String) {
        if (text.isBlank()) return
        val pinned = list.firstOrNull { it.text == text }?.pinned ?: false
        list.removeAll { it.text == text }
        list.add(0, Entry(text, pinned))
        val unpinned = list.filter { !it.pinned }
        if (unpinned.size > 40) list.remove(unpinned.last())
        save()
    }

    fun togglePin(text: String) {
        val i = list.indexOfFirst { it.text == text }
        if (i >= 0) { list[i] = list[i].copy(pinned = !list[i].pinned); save() }
    }

    fun clearUnpinned() { list.removeAll { !it.pinned }; save() }

    fun entries(): List<Entry> = list.filter { it.pinned } + list.filter { !it.pinned }
}
