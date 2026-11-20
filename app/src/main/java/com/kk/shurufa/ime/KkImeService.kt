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
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.Gravity
import android.inputmethodservice.InputMethodService.Insets
import android.animation.ValueAnimator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.ClipStrip
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.keyboard.EditPanel
import com.kk.shurufa.keyboard.FamilyRows
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyOverride
import com.kk.shurufa.keyboard.KeyStyle
import com.kk.shurufa.keyboard.KeyboardLayout
import com.kk.shurufa.keyboard.KeyboardListener
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.MathLayout
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PopupOverlay
import com.kk.shurufa.keyboard.QuickPanel
import com.kk.shurufa.keyboard.SheetHandle
import com.kk.shurufa.keyboard.SymbolCategory
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import com.kk.shurufa.settings.KeyOverrides
import com.kk.shurufa.settings.SettingsActivity
import java.io.File
import kotlin.math.abs

class KkImeService : InputMethodService(), KeyboardListener, CandidateBar.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var repo: LayoutRepo
    private lateinit var theme: KbTheme
    private lateinit var haptics: Haptics
    private lateinit var predictor: Predictor
    private lateinit var tones: PinyinTones

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private var navInset = 0
    /** Height told to the app (from the bottom); -1 means the column's current height. */
    private var reportH = -1
    private lateinit var bar: CandidateBar
    private lateinit var content: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var overlay: PopupOverlay
    private lateinit var candPanel: LinearLayout
    private lateinit var candGrid: GridPanel
    private lateinit var symbolPanel: LinearLayout
    private lateinit var symRail: GridPanel
    private lateinit var symCells: GridPanel
    private lateinit var symBottom: KeyboardView
    private lateinit var editPanel: EditPanel
    private lateinit var mathPanel: com.kk.shurufa.keyboard.MathPanel
    private lateinit var clipPanel: LinearLayout
    private lateinit var clipStrip: ClipStrip
    private lateinit var clipFavs: GridPanel
    private lateinit var quickPanel: QuickPanel

    enum class Panel { LETTERS, NUM, SYMBOLS, MATH, PHONE, CURSOR, CLIPBOARD, CANDIDATES, QUICK, EMOJI, VOICE, AI }

    private lateinit var aiPanel: com.kk.shurufa.keyboard.AiPanel
    private lateinit var translateStrip: com.kk.shurufa.keyboard.TranslateStrip
    private var aiResult = ""
    private var aiFromSelection = false
    // 翻译: like 表情查找, the keyboard types into [searchQuery]; the bar shows the translation
    private var translateMode = false
    private var translateTo = "en"
    private var translateResult = ""
    private var translatedFor = ""
    private val translateRun = Runnable { runTranslate() }
    private val sinkOn get() = emojiSearch || translateMode

    private lateinit var voicePanel: LinearLayout
    private lateinit var voiceView: com.kk.shurufa.keyboard.VoicePanel
    private var voice: com.kk.shurufa.voice.VoiceEngine? = null
    /** Recognition language picked on the panel for this session; null follows the setting. */
    private var voiceLangPick: String? = null

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
    private var symCat = ""
    private var symLock = false
    private var symItems: List<Output> = emptyList()
    private var recentSymbols = mutableListOf<String>()

    private var selStart = 0
    private var selEnd = 0
    private var selectMode = false

    private lateinit var clips: ClipHistory
    private var clipRecent: List<ClipHistory.Entry> = emptyList()
    private var clipFavItems: List<ClipHistory.Entry> = emptyList()
    private var heightAnim: ValueAnimator? = null
    /** 拼音编辑: the bar shows the typed letters large, with a caret that can be placed precisely. */
    private var pinyinEdit = false
    private var pendingClip: String? = null
    private var pendingClipAt = 0L

    private var predictions: List<String> = emptyList()
    private var predictionLabel = ""
    /** 临时拉高: the clipboard and the candidate grid can take more of the screen. */
    private var tall = false
    // 退格回退: the last word that came from Rime, with the pinyin that produced it
    private var lastCommitText = ""
    private var lastCommitInput = ""
    private var lastCommitAt = 0L
    private var inputBeforeKey = ""
    private var lastSymbolsAt = 0L

    // 删除后联想: each committed character with the syllable that produced it, aligned with the
    // end of the text; deleting characters collects their syllables to offer the word again.
    private val charLog = ArrayDeque<Pair<String, String>>()
    private val deletedRun = ArrayDeque<String>()

    private lateinit var emojiPanel: LinearLayout
    private lateinit var emojiRail: GridPanel
    private lateinit var emojiCells: GridPanel
    private lateinit var emojiData: EmojiData
    /** Emoji groups the phone can draw, worked out once per data version. */
    private var emojiGroups: List<Pair<String, List<String>>> = emptyList()
    private var kaomoji: List<String> = emptyList()
    /** Tab of the emoji panel: 0 最近, 1..n the groups, n+1 颜文字. */
    private var emojiGroup = 0
    private var emojiShown: List<String> = emptyList()
    private val recentEmoji = mutableListOf<String>()
    private val recentKao = mutableListOf<String>()
    /** 肤色: the variant last chosen for an emoji, shown in its place in the grid. */
    private val toneChoice = HashMap<String, String>()
    // 表情查找: the keyboard types into a query instead of the app, the bar shows matches
    private var emojiSearch = false
    private val searchQuery = StringBuilder()
    private var searchResults: List<String> = emptyList()
    private var commonChars: Set<Char>? = null
    private val history = EditHistory()

    // 拼音模式: the syllables of the chosen part of the composition, kept as it is confirmed
    private val confirmedSyllables = mutableListOf<String>()
    private var lastHanPrefix = 0
    private var lastSyllables: List<String> = emptyList()

    private val rimeListener: (RimeEngine.State) -> Unit = { onRimeState(it) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = LayoutRepo(this, overrides = { KeyOverrides.load(prefs, it) })
        haptics = Haptics(this, prefs)
        predictor = Predictor(this)
        tones = PinyinTones(this)
        if (prefs.zhPredict) predictor.load()
        if (prefs.packPoetry) predictor.loadPoetry()
        recentEmoji += prefs.sp.getString("emoji_recent", "")!!.split(' ').filter { it.isNotEmpty() }
        recentKao += prefs.sp.getString("kao_recent", "")!!.split('\u0001').filter { it.isNotEmpty() }
        prefs.sp.getString("emoji_tone", "")!!.split('\u0002').forEach { p ->
            val i = p.indexOf('\u0001'); if (i > 0) toneChoice[p.substring(0, i)] = p.substring(i + 1)
        }
        emojiData = EmojiData(this)
        loadEmoji()
        maybeUpdateEmoji()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        recentSymbols = prefs.sp.getString("sym_recent", "")!!.split('\u0001').filter { it.isNotEmpty() }.toMutableList()
        clips = ClipHistory(File(filesDir, "clipboard.txt")) { prefs.clipKeep }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        RimeEngine.addListener(rimeListener)
        RimeEngine.start(this)
        lang = prefs.languages.firstOrNull() ?: "zh"
    }

    override fun onDestroy() {
        voice?.release()
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
        if (text.isBlank()) return@OnPrimaryClipChangedListener
        clips.add(text)
        if (prefs.clipSuggest) { pendingClip = text; pendingClipAt = SystemClock.uptimeMillis() }
        if (::bar.isInitialized) { if (panel == Panel.CLIPBOARD) refreshClips(); syncBar() }
    }

    /** Settings that only change behavior; everything else rebuilds the keyboard. */
    private val liveKeys = setOf(
        "sym_recent", "emoji_recent", Prefs.TOOLBAR_ITEMS, "kao_recent", "emoji_tone", Prefs.EMOJI_AUTO_UPDATE, Prefs.AI_KEY, Prefs.AI_MODEL, Prefs.TRANSLATE_TARGET, Prefs.TRANSLATE_WIFI,
        Prefs.VOICE_ENGINE, Prefs.VOICE_MODEL, Prefs.VOICE_SOURCE, Prefs.VOICE_LANG, Prefs.VOICE_MAX_SEC,
        Prefs.TRANSLATE_ENGINE, Prefs.AI_PROVIDER, Prefs.VOICE_ZH_MODEL, Prefs.VOICE_PUNCT, Prefs.VOICE_JOIN_COMMA, Prefs.VOICE_PANGU,
        Prefs.VOICE_PAUSE_MS, Prefs.VOICE_AUTO_STOP, Prefs.DRAG_CANCEL, Prefs.HOLD_CANCEL_MS, Prefs.VOICE_RETURN, Prefs.VOICE_EN_MODEL,
        Prefs.VOICE_FILLERS, Prefs.VOICE_COMMIT_PAUSE, Prefs.ZH_TRAD, Prefs.ZH_EMOJI, Prefs.ZH_PREDICT, Prefs.PINYIN_MODE, Prefs.MATH_FORMAT,
        Prefs.VIBRATE, Prefs.SOUND, Prefs.CLIP_KEEP, Prefs.CLIP_SUGGEST, Prefs.ZH_CORRECTION, Prefs.STRESS_MODE,
        Prefs.BACKSPACE_RESELECT, Prefs.NUM_MODE, Prefs.TYPO_MARK, Prefs.RARE_ANNOTATE, Prefs.PACK_POETRY, Prefs.SYMBOL_MEMORY,
        Prefs.CLIP_MAX_CHARS, Prefs.CLIP_FRICTION, Prefs.CLIP_COLUMNS,
    )

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key != null && key.startsWith("hap_")) { haptics.invalidate(); return }
        if (key == "seen_emoji_reload") { loadEmoji(); return }
        // settings that change behavior only must never rebuild the keyboard: that resets open panels
        if (key == null || key.startsWith("app_lang_") || key.startsWith("seen_") || key.startsWith("llm_")) return
        when (key) {
            Prefs.ZH_EMOJI, Prefs.ZH_TRAD -> applyRimeOptions()
            Prefs.ZH_PREDICT -> if (prefs.zhPredict) predictor.load() else { predictions = emptyList(); syncBar() }
            Prefs.ZH_CORRECTION -> RimeEngine.redeploy(this)
            Prefs.PACK_POETRY -> { if (prefs.packPoetry) predictor.loadPoetry() else predictor.dropPoetry(); RimeEngine.redeploy(this) }
            Prefs.MATH_FORMAT -> if (::bar.isInitialized) syncBar()
            Prefs.STRESS_MODE -> if (::keyboard.isInitialized && panel == Panel.LETTERS) keyboard.setRows(lettersRows())
            Prefs.CLIP_FRICTION, Prefs.CLIP_MAX_CHARS, Prefs.CLIP_COLUMNS -> if (::clipFavs.isInitialized) {
                clipFavs.friction = prefs.clipFriction; clipFavs.columns = prefs.clipColumns; clipFavs.textSp = clipFavText(); refreshClips(keep = true)
            }
        }
        if (key in liveKeys) { if (panel == Panel.QUICK && ::quickPanel.isInitialized) refreshQuick(); return }
        layouts.clear(); math = null
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
        // The input window keeps one tall, mostly transparent size. Panels then change height inside
        // it, so no animation or drag ever resizes the window or makes the app below lay out again;
        // onComputeInsets tells the app (and touch dispatch) where the keyboard actually is.
        root = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(windowH(), MeasureSpec.EXACTLY))
            }
        }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.background) }
        overlay = PopupOverlay(this, prefs).also { it.theme = theme; it.onTick = { haptics.play(HapticEvent.OPTION) } }
        bar = CandidateBar(this, prefs).also { it.theme = theme; it.listener = this; it.overlay = overlay }
        // 按住工具栏拖动: with a resizable panel open, the toolbar works as its grab bar too
        bar.onVerticalDrag = { phase, rawY, v ->
            val h = when (panel) { Panel.CLIPBOARD -> clipHandle; else -> null }
            if (h != null) when (phase) { 0 -> h.begin(rawY); 1 -> h.drag(rawY); else -> h.end(v) }
        }
        content = FrameLayout(this)
        keyboard = newKeyboard()
        buildCandidatePanel()
        buildSymbolPanel()
        buildEditPanel()
        buildMathPanel()
        buildVoicePanel()
        buildAiPanel()
        buildTranslateStrip()
        buildClipPanel()
        buildEmojiPanel()
        quickPanel = QuickPanel(this, prefs).also { it.theme = theme; it.onTile = { id -> onQuickTile(id) }; it.onPress = { haptics.play(HapticEvent.LETTER) } }
        column.addView(translateStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
            navInset = bottom
            column.setPadding(0, 0, 0, bottom)
            ins
        }
        paintNavigationBar()
        showPanel(panel)
        return root
    }

    /** Tall enough for the tallest panel plus room above it for key previews and long-press menus. */
    private fun windowH(): Int {
        val tallest = maxOf(tallH(), mathH() + dp(8f).toInt()) + barH
        return minOf((resources.displayMetrics.heightPixels * 0.86f).toInt(), tallest + navInset + dp(96f).toInt())
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::root.isInitialized || root.height == 0 || !root.isShown) return
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val bottom = loc[1] + root.height
        val shown = column.height
        // while a panel is dragged taller it covers the app; once it rests, the app makes room once,
        // so what is pasted is not hidden behind it
        var reported = if (reportH > 0) reportH else shown
        val sheet = if (panel == Panel.CLIPBOARD) clipHandle else if (panel == Panel.CANDIDATES) candHandle else null
        if (sheet != null && sheet.moving) reported = minOf(reported, baseH + barH + navInset)
        val top = bottom - reported
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, minOf(top, bottom - shown), root.width, bottom)
    }

    /** The system draws its IME buttons below the keys; give that strip the keyboard's color. */
    private fun paintNavigationBar() {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        w.navigationBarColor = theme.background
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.setSystemBarsAppearance(
                if (theme.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (theme.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun newKeyboard() = KeyboardView(this, prefs).also { it.theme = theme; it.listener = this; it.overlay = overlay }

    private fun loadData() {
        try {
            math = repo.math()
            symbols = repo.symbols()
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
        predictions = emptyList()
        history.clear()
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
            (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) && prefs.numberFieldMath -> { mathTab = "basic"; numPanel() }
            else -> Panel.LETTERS
        }
        shift = 0; autoShift = false
        if (::root.isInitialized) { paintNavigationBar(); showPanel(panel) }
        updateAutoCap()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        emojiSearch = false; translateMode = false; searchQuery.setLength(0)
        if (::translateStrip.isInitialized) translateStrip.visibility = View.GONE
        voice?.cancel()
        if (RimeEngine.ready) RimeBridge.clearComposition()
        resetSyllables()
        predictions = emptyList()
        currentInputConnection?.finishComposingText()
        if (::bar.isInitialized) syncBar()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (::editPanel.isInitialized && panel == Panel.CURSOR) syncEditPanel()
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

    private val vowels = "аеёиоуыэюя"

    private fun lettersRows(): List<List<Key>> {
        var base = layout(curLang).rows
        if (curLang == "ru" && prefs.stressMode) {
            // 重音模式: every Russian vowel flicks up to its stressed form
            base = base.map { row ->
                row.map { k ->
                    val ch = k.main?.text
                    if (ch != null && ch.length == 1 && ch[0] in vowels) k.copy(flicks = k.flicks + (Dir.UP to Output.of(ch + "́")))
                    else k
                }
            }
        }
        if (prefs.topRowScripts && base.isNotEmpty()) {
            // 第一排左右滑: superscript and subscript digits
            val sup = "¹²³⁴⁵⁶⁷⁸⁹⁰"; val sub = "₁₂₃₄₅₆₇₈₉₀"
            base = listOf(base[0].mapIndexed { i, k ->
                if (i < 10 && k.main != null) k.copy(flicks = k.flicks + mapOf(Dir.LEFT to Output.of(sup[i].toString()), Dir.RIGHT to Output.of(sub[i].toString())))
                else k
            }) + base.drop(1)
        }
        if (!prefs.numberRow || base.isEmpty()) return base
        val digits = (1..10).map { Key(main = Output.of((it % 10).toString())) }
        return listOf(digits) + base
    }

    private fun kbHeight(rows: Int): Int {
        val keyH = dp(prefs.keyHeightDp.toFloat())
        return (dp(6f) + dp(prefs.bottomPaddingDp + 2f) + rows * keyH + (rows - 1) * dp(prefs.keyGapDp)).toInt()
    }

    private fun toolFor(p: Panel) = when (p) {
        Panel.CURSOR -> "cursor"
        Panel.CLIPBOARD -> "clipboard"
        Panel.QUICK -> "quick"
        Panel.EMOJI -> "emoji"
        Panel.VOICE -> "mic"
        Panel.AI -> "ai"
        else -> null
    }

    private val barH get() = dp(50f).toInt()
    private val baseH get() = kbHeight(lettersRows().size.coerceAtLeast(4))
    /** 学术 is taller than the letters: a tab row, four rows of keys and the bottom row. */
    private fun mathH(): Int {
        val kh = dp(prefs.keyHeightDp.toFloat())
        val g = dp(prefs.keyGapDp)
        return (dp(38f) + dp(2f) + 4 * kh * com.kk.shurufa.keyboard.MathPanel.ROW + 3 * g + g + kh + dp(prefs.bottomPaddingDp + 2f)).toInt()
    }
    private fun tallH() = maxOf(baseH + dp(40f).toInt(), (resources.displayMetrics.heightPixels * 0.56f).toInt() - barH)
    private fun inFamily(p: Panel) = p == Panel.NUM || p == Panel.MATH || p == Panel.SYMBOLS

    private fun showPanel(p: Panel) {
        if (p != Panel.LETTERS && sinkOn) {
            emojiSearch = false; translateMode = false; searchQuery.setLength(0)
            if (::translateStrip.isInitialized) translateStrip.visibility = View.GONE
        }
        if (p != Panel.VOICE && panel == Panel.VOICE) voice?.cancel()
        val from = panel
        panel = p
        if (p != Panel.LETTERS && p != Panel.CANDIDATES) pinyinEdit = false
        val oldTotal = (if (bar.visibility == View.VISIBLE) bar.height else 0) + content.height
        content.removeAllViews()
        heightAnim?.cancel()
        reportH = -1
        val base = baseH
        val tallH = tallH()
        val canTall = p == Panel.CLIPBOARD || p == Panel.CANDIDATES
        // 记住符号: back to the symbols only if they were the last panel of the family used
        if (from == Panel.SYMBOLS && p != Panel.SYMBOLS) lastSymbolsAt = if (inFamily(p)) 0L else SystemClock.uptimeMillis()
        listOf(clipHandle, candHandle).forEach { it.low = base; it.high = tallH; it.current = if (tall) tallH else base }
        // the symbol and edit panels hide the toolbar and use its height themselves
        val noBar = p == Panel.SYMBOLS
        bar.visibility = if (noBar) View.GONE else View.VISIBLE
        val h = when {
            p == Panel.MATH -> -1
            noBar -> base + barH
            canTall && tall -> tallH
            p == Panel.LETTERS || p == Panel.PHONE -> -1
            else -> base
        }
        // children sit at the bottom: while the area grows or shrinks, the top edge moves
        val fixed = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (h < 0) ViewGroup.LayoutParams.WRAP_CONTENT else h, Gravity.BOTTOM)
        keyboard.translationX = 0f
        when (p) {
            Panel.LETTERS -> { keyboard.setRows(lettersRows()); content.addView(keyboard, fixed) }
            Panel.PHONE -> { keyboard.setRows(layout("phone").rows); content.addView(keyboard, fixed) }
            Panel.MATH -> {
                if (math == null) { showPanel(Panel.LETTERS); return }
                refreshMath(slide = 0)
                content.addView(mathPanel, fixed)
            }
            Panel.NUM -> { keyboard.setRows(layout("num").rows); content.addView(keyboard, fixed) }
            Panel.CURSOR -> { syncEditPanel(); content.addView(editPanel, fixed); if (from != p) animateIn(editPanel) }
            Panel.SYMBOLS -> { refreshSymbols(); content.addView(symbolPanel, fixed); if (from != p) animateIn(symbolPanel) }
            Panel.CLIPBOARD -> { refreshClips(); content.addView(clipPanel, fixed); if (from != p) animateIn(clipPanel) }
            Panel.QUICK -> { refreshQuick(); content.addView(quickPanel, fixed); quickPanel.animateIn() }
            Panel.EMOJI -> { refreshEmoji(); content.addView(emojiPanel, fixed); if (from != p) animateIn(emojiPanel) }
            Panel.AI -> { content.addView(aiPanel, fixed); if (from != p) { animateIn(aiPanel); onAiOpened() } }
            Panel.VOICE -> { content.addView(voicePanel, fixed); if (from != p) { animateIn(voicePanel); onVoiceOpened() } }
            Panel.CANDIDATES -> {
                val cands = RimeBridge.candidates(300).toList()
                candGrid.notes = annotations(cands)
                candGrid.setItems(cands)
                content.addView(candPanel, fixed)
                animateIn(candPanel)
            }
        }
        bar.expanded = p == Panel.CANDIDATES
        bar.activeTool = if (emojiSearch) "emoji" else if (translateMode) "translate" else toolFor(p)
        syncBar()
        animateHeight(oldTotal, if (noBar) 0 else barH)
    }

    /** Eases the keyboard's height from what it was to what the new panel needs. */
    private fun animateHeight(oldTotal: Int, newBar: Int) {
        if (!prefs.animations || oldTotal <= 0) return
        content.measure(View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val target = content.measuredHeight
        val start = oldTotal - newBar
        if (start <= 0 || abs(start - target) < dp(2f)) return
        // while it moves the app keeps the larger of the two sizes, so it lays out at most once
        reportH = maxOf(start, target) + newBar + navInset
        heightAnim = ValueAnimator.ofInt(start, target).apply {
            duration = (230 * prefs.animSpeed).toLong()
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { content.layoutParams = content.layoutParams.apply { height = it.animatedValue as Int }; content.requestLayout() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    reportH = -1
                    content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }; content.requestLayout()
                }
            })
            start()
        }
    }

    private fun animateIn(v: View) {
        if (!prefs.animations) return
        v.alpha = 0f; v.translationY = dp(12f)
        v.animate().alpha(1f).translationY(0f).setDuration((190 * prefs.animSpeed).toLong())
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).withLayer().start()
    }

    private fun syncBar() {
        if (!::bar.isInitialized) return
        val clipFresh = pendingClip != null && SystemClock.uptimeMillis() - pendingClipAt < 90_000
        when {
            panel == Panel.MATH -> bar.showMath(emptyList(), mathTab, prefs.mathFormat, simple = false)
            panel == Panel.NUM -> bar.showMath(emptyList(), "", prefs.mathFormat, simple = true)
            isZh && RimeEngine.state == RimeEngine.State.DEPLOYING && panel == Panel.LETTERS -> bar.showMessage("正在部署雾凇拼音词库，首次约需一分钟…")
            isZh && RimeEngine.state == RimeEngine.State.PREPARING && panel == Panel.LETTERS -> bar.showMessage("正在准备中文词库…")
            isZh && RimeEngine.state == RimeEngine.State.FAILED -> bar.showMessage("中文引擎启动失败：${RimeEngine.error ?: "未知错误"}")
            translateMode && !composing && panel == Panel.LETTERS -> { bar.showToolbar(); syncTranslate() }
            emojiSearch && !composing && panel == Panel.LETTERS -> {
                val q = searchQuery.toString().trim()
                val drawable = emojiGroups.flatMapTo(HashSet()) { it.second }
                searchResults = emojiData.search(q, 120).filter { it in drawable }
                bar.showPredictions(searchResults, when {
                    q.isEmpty() -> "查找表情：输入中文或英文关键词"
                    searchResults.isEmpty() -> "没有找到和「$q」相关的表情"
                    else -> "查找「$q」· 点按发送"
                })
            }
            composing && pinyinEdit -> bar.showEdit(RimeBridge.input(), RimeBridge.caretPos(), syllableGaps())
            composing -> {
                trackSyllables()
                val cands = RimeBridge.candidates(80).toList()
                bar.showCandidates(RimeBridge.preedit(), cands, annotations(cands), caretInPreedit())
                if (prefs.zhPreeditInline) currentInputConnection?.setComposingText(RimeBridge.input(), 1)
            }
            predictions.isNotEmpty() && panel == Panel.LETTERS -> bar.showPredictions(predictions, predictionLabel)
            clipFresh && panel == Panel.LETTERS -> bar.showClip(pendingClip!!, ClipHistory.kind(pendingClip!!))
            else -> {
                bar.showToolbar()
                if (panel == Panel.CANDIDATES) showPanel(Panel.LETTERS)
            }
        }
    }

    private fun buildCandidatePanel() {
        candPanel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candGrid = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; cellStyle = GridPanel.CellStyle.FLAT; columns = 8; rowHeightDp = 50f; textSp = 19f
            highlightFirst = true; overlay = this@KkImeService.overlay
            onItem = { i -> onCandidate(i) }
            onFeedback = { k -> haptics.play(if (k == "popup") HapticEvent.POPUP else HapticEvent.LETTER) }
            longOptions = { i -> candidateOptions(i) }
            onLongOption = { i, o -> onCandidateOption(i, o) }
        }
        val side = newKeyboard().apply {
            heightScale = 0.9f
            setRows(listOf(
                listOf(Key(fn = "collapse", label = "收起")),
                listOf(Key(fn = "backspace")),
                listOf(Key(fn = "reinput", label = "重输")),
            ))
        }
        candHandle = newHandle()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(candGrid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(side, LinearLayout.LayoutParams(dp(68f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        candPanel.orientation = LinearLayout.VERTICAL
        candPanel.addView(candHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        candPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private lateinit var clipHandle: SheetHandle
    private lateinit var candHandle: SheetHandle

    /** 面板高度档位: dragging the grab bar resizes the open panel live; it settles on one of two heights. */
    private fun newHandle() = SheetHandle(this, prefs).apply {
        theme = this@KkImeService.theme
        onFeedback = { k -> haptics.play(if (k == "gesture") HapticEvent.GESTURE else HapticEvent.OPTION) }
        onHeight = { h -> content.getChildAt(0)?.let { v -> v.layoutParams = v.layoutParams.apply { height = h }; v.requestLayout() } }
        onSettled = { t -> tall = t }
        onRest = { root.requestLayout() }
    }

    private fun buildSymbolPanel() {
        symbolPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        symRail = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; columns = 1; rowHeightDp = 36f; textSp = 13.5f; cellStyle = GridPanel.CellStyle.RAIL
            onItem = { i -> symCat = symCatIds().getOrElse(i) { "recent" }; refreshSymbols() }
            onFeedback = { haptics.play(HapticEvent.FUNCTION) }
        }
        symCells = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; cellStyle = GridPanel.CellStyle.SOFT; rowHeightDp = 42f
            emptyText = "用过的符号会出现在这里"
            onItem = { i -> symItems.getOrNull(i)?.let { onSymbol(it) } }
            onFeedback = { haptics.play(HapticEvent.LETTER) }
        }
        top.addView(symRail, LinearLayout.LayoutParams(dp(60f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(symCells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        symBottom = newKeyboard()
        symBottom.setRows(listOf(FamilyRows.symbols()))
        symbolPanel.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        symbolPanel.addView(symBottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 简易/科学: !#1 and number fields open the number keyboard in the mode used last. */
    private fun numPanel() = if (prefs.numMode == "sci") Panel.MATH else Panel.NUM

    private fun showCalcResult() {
        if (!prefs.mathCalc) return
        val before = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: return
        val line = before.substringAfterLast('\n').dropLast(1)
        Calc.eval(line.substringAfterLast('='))?.let { bar.showResult(Calc.format(it)) }
    }

    private fun buildEditPanel() {
        editPanel = EditPanel(this, prefs).apply {
            theme = this@KkImeService.theme
            labels = prefs.cursorLabels
            onFeedback = { k -> haptics.play(when (k) { "repeat" -> HapticEvent.REPEAT; "touch" -> HapticEvent.FUNCTION; else -> HapticEvent.CURSOR }) }
            onAction = { id -> onEditAction(id) }
        }
    }

    private fun syncEditPanel() {
        editPanel.selecting = selectMode
        editPanel.hasSelection = selStart != selEnd
    }

    private fun onEditAction(id: String) {
        val ic = currentInputConnection
        when (id) {
            "undo" -> undo()
            "select" -> {
                selectMode = !selectMode
                // leaving selection mode drops the selection back to a caret at its end
                if (!selectMode && selStart != selEnd) ic?.setSelection(selEnd, selEnd)
            }
            "up" -> caretStep(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> caretStep(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> caretStep(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> caretStep(KeyEvent.KEYCODE_DPAD_RIGHT)
            "home" -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, shiftMeta())
            "end" -> sendKey(KeyEvent.KEYCODE_MOVE_END, shiftMeta())
            "select_all" -> { ic?.performContextMenuAction(android.R.id.selectAll); selectMode = true }
            "backspace" -> { haptics.play(HapticEvent.REPEAT); deleteBackward() }
            "copy" -> { ic?.performContextMenuAction(android.R.id.copy); selectMode = false; ic?.setSelection(selEnd, selEnd) }
            "cut" -> { ic?.performContextMenuAction(android.R.id.cut); selectMode = false }
            "paste" -> { ic?.performContextMenuAction(android.R.id.paste); selectMode = false }
        }
        syncEditPanel()
    }

    private fun symCatIds() = listOf("recent") + symbols.map { it.id }

    private fun refreshSymbols() {
        if (symbols.isEmpty()) return
        // 最近 holds what was typed; with nothing typed yet the panel opens on the first category
        if (symCat.isEmpty()) symCat = if (recentSymbols.isNotEmpty()) "recent" else symbols.first().id
        val ids = symCatIds()
        val idx = ids.indexOf(symCat).coerceAtLeast(0)
        val cat = symbols.getOrNull(idx - 1)
        // 最近 holds only what was actually typed; 常用 is the fixed default set
        symItems = when {
            idx == 0 -> recentSymbols.map { Output.of(it) }
            else -> cat?.items.orEmpty()
        }
        val wide = cat?.wide == true
        symRail.selectedIndex = idx
        symRail.setItems(listOf("最近") + symbols.map { it.name }, resetScroll = false)
        symCells.columns = if (wide) 3 else 6
        symCells.textSp = if (wide) 14f else 18.5f
        symCells.marks = emptySet()
        symCells.setItems(symItems.map { it.label })
        symBottom.lockOn = symLock
        symBottom.invalidate()
    }

    private fun recordSymbol(text: String) {
        recentSymbols.remove(text)
        recentSymbols.add(0, text)
        while (recentSymbols.size > 30) recentSymbols.removeAt(recentSymbols.size - 1)
        prefs.sp.edit().putString("sym_recent", recentSymbols.joinToString("\u0001")).apply()
    }

    private fun onSymbol(o: Output) {
        commitComposingFirst()
        lastSymbolsAt = SystemClock.uptimeMillis()
        commit(o.text)
        recordSymbol(o.text)
        if (!symLock && prefs.symbolAutoReturn) showPanel(Panel.LETTERS)
    }

    /**
     * 剪贴板: 最近 on top as a sideways strip, 收藏 below as a vertical list. A tap pastes; everything
     * else is in the long-press menu. The grab bar (or the toolbar above it) drags the panel taller.
     */
    private fun buildClipPanel() {
        clipPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fb: (String) -> Unit = { k -> haptics.play(when (k) { "popup" -> HapticEvent.POPUP; "tick" -> HapticEvent.OPTION; else -> HapticEvent.LETTER }) }
        clipStrip = ClipStrip(this, prefs).apply {
            theme = this@KkImeService.theme; overlay = this@KkImeService.overlay
            emptyText = "复制过的文字会出现在这里"
            onFeedback = fb
            onTap = { i -> clipRecent.getOrNull(i)?.let { pasteClip(it.text) } }
            longOptions = { i -> clipRecent.getOrNull(i)?.let { e ->
                listOf(Output("取消", "§cancel"), Output(if (e.pinned) "取消收藏" else "收藏", "§pin"), Output("删除", "§del"), Output("清空最近", "§clear"))
            }.orEmpty() }
            onLongOption = { i, o -> clipRecent.getOrNull(i)?.let { onClipOption(it, o.text) } }
        }
        clipFavs = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; overlay = this@KkImeService.overlay
            cellStyle = GridPanel.CellStyle.CARD; columns = prefs.clipColumns; rowHeightDp = 66f; textSp = clipFavText()
            friction = prefs.clipFriction
            emptyText = "长按「最近」里的一条，把它收藏到这里"
            onFeedback = fb
            onItem = { i -> clipFavItems.getOrNull(i)?.let { pasteClip(it.text) } }
            longOptions = { i -> if (i in clipFavItems.indices) listOf(Output("取消", "§cancel"), Output("取消收藏", "§pin"), Output("移到最前", "§top"), Output("删除", "§del")) else emptyList() }
            onLongOption = { i, o -> clipFavItems.getOrNull(i)?.let { onClipOption(it, o.text) } }
        }
        clipHandle = newHandle()
        // the strip and the gaps between favorites resize the panel too, not only the grab bar
        val drag: (Int, Float, Float) -> Unit = { phase, y, v -> when (phase) { 0 -> clipHandle.begin(y); 1 -> clipHandle.drag(y); else -> clipHandle.end(v) } }
        clipStrip.onVerticalDrag = drag
        clipFavs.onGapDrag = drag
        clipPanel.addView(clipHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clipPanel.addView(clipStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118f).toInt()))
        clipPanel.addView(clipFavs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Narrower favorite cards use slightly smaller text so a line still holds a few words. */
    private fun clipFavText() = when (prefs.clipColumns) { 2 -> 13.5f; 3 -> 12.5f; else -> 11.5f }

    private fun pasteClip(text: String) {
        commitComposingFirst()
        commit(text, pairable = false)
        history.push(EditHistory.Op.Insert(text))
    }

    private fun onClipOption(e: ClipHistory.Entry, op: String) {
        when (op) {
            "§pin" -> clips.togglePin(e.text)
            "§del" -> clips.remove(e.text)
            "§clear" -> clips.clearUnpinned()
            "§top" -> clips.moveToFront(e.text)
            else -> return
        }
        haptics.play(HapticEvent.GESTURE)
        refreshClips(keep = op != "§clear")
    }

    private fun clipShort(t: String): String {
        val s = t.replace(Regex("\\s+"), " ").trim()
        return if (s.length > prefs.clipMaxChars) s.take(prefs.clipMaxChars) + "…" else s
    }

    private fun refreshClips(keep: Boolean = false) {
        clipRecent = clips.entries()
        clipFavItems = clips.favorites()
        clipStrip.setCards(clipRecent.map { ClipStrip.Card(it.text, ClipHistory.kind(it.text), it.pinned) }, keep)
        clipFavs.header = if (clipFavItems.isEmpty()) "收藏" else "收藏 ${clipFavItems.size}"
        clipFavs.tags = clipFavItems.map { ClipHistory.kind(it.text) }
        clipFavs.setItems(clipFavItems.map { clipShort(it.text) }, resetScroll = !keep)
    }

    private fun refreshQuick() {
        val themeName = when (prefs.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "系统" }
        quickPanel.tiles = listOf(
            QuickPanel.Tile("trad", "繁体", "简繁切换", prefs.zhTraditional),
            QuickPanel.Tile("emoji", "Emoji", "候选里带表情", prefs.zhEmoji),
            QuickPanel.Tile("predict", "联想", "推荐下一个词", prefs.zhPredict),
            QuickPanel.Tile("correct", "纠错", "拼音按键纠错", prefs.zhCorrection),
            QuickPanel.Tile("pinyin", "拼音模式", "上屏带调拼音", prefs.pinyinMode),
            QuickPanel.Tile("stress", "重音模式", "俄语重音字母", prefs.stressMode),
            QuickPanel.Tile("latex", "LaTeX", "数学键盘输出", prefs.mathFormat == "tex"),
            QuickPanel.Tile("theme", "主题", themeName),
            QuickPanel.Tile("vibrate", "振动", "", prefs.vibrate),
            QuickPanel.Tile("sound", "按键音", "", prefs.sound),
            QuickPanel.Tile("math", "数学键盘"),
            QuickPanel.Tile("symbols", "全部符号"),
            QuickPanel.Tile("cursor", "文字编辑"),
            QuickPanel.Tile("clipboard", "剪贴板"),
            QuickPanel.Tile("picker", "切换输入法"),
            QuickPanel.Tile("toolbar", "工具栏"),
            QuickPanel.Tile("settings", "完整设置"),
        )
    }

    private fun onQuickTile(id: String) {
        when (id) {
            "trad" -> prefs.put(Prefs.ZH_TRAD, !prefs.zhTraditional)
            "emoji" -> prefs.put(Prefs.ZH_EMOJI, !prefs.zhEmoji)
            "predict" -> prefs.put(Prefs.ZH_PREDICT, !prefs.zhPredict)
            "correct" -> { prefs.put(Prefs.ZH_CORRECTION, !prefs.zhCorrection); Toast.makeText(this, "正在重新部署词库…", Toast.LENGTH_SHORT).show() }
            "pinyin" -> prefs.put(Prefs.PINYIN_MODE, !prefs.pinyinMode)
            "stress" -> prefs.put(Prefs.STRESS_MODE, !prefs.stressMode)
            "latex" -> prefs.put(Prefs.MATH_FORMAT, if (prefs.mathFormat == "tex") "uni" else "tex")
            "theme" -> prefs.put(Prefs.THEME, when (prefs.theme) { "system" -> "light"; "light" -> "dark"; else -> "system" })
            "vibrate" -> prefs.put(Prefs.VIBRATE, !prefs.vibrate)
            "sound" -> prefs.put(Prefs.SOUND, !prefs.sound)
            "math" -> onFunction(Key(fn = "sci"))
            "symbols" -> showPanel(Panel.SYMBOLS)
            "cursor" -> showPanel(Panel.CURSOR)
            "clipboard" -> showPanel(Panel.CLIPBOARD)
            "picker" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            "settings" -> openSettings()
            "toolbar" -> openSettings("toolbar")
        }
        if (panel == Panel.QUICK) refreshQuick()
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
        inputBeforeKey = RimeBridge.input()
        val handled = RimeBridge.processKey(code, mask)
        flushRime()
        return handled
    }

    private fun flushRime() {
        val commit = RimeBridge.commitText()
        if (commit.isNotEmpty() && sinkOn) {
            // 表情查找: the chosen word goes into the query, not the app
            resetSyllables(); inputBeforeKey = ""
            searchQuery.append(commit)
        } else if (commit.isNotEmpty()) {
            val syl = confirmedSyllables + lastSyllables
            var out = if (prefs.pinyinMode && commit.any { isCjk(it) }) tones.convert(commit, syl) else commit
            if (prefs.pinyinMode && out != commit) {
                // 拼音模式 separates consecutive words with a space
                val prev = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                if (prev.isNotEmpty() && prev[0].isLetterOrDigit() && !isCjk(prev[0])) out = " $out"
            }
            resetSyllables()
            commit(out, pairable = false, syllables = if (out == commit) syl else null)
            history.push(EditHistory.Op.Insert(out))
            inputBeforeKey = ""
            predictions = if (prefs.zhPredict && !prefs.pinyinMode && out.any { isCjk(it) })
                predictor.suggest(currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: out) else emptyList()
            predictionLabel = ""
        } else if (prefs.zhPreeditInline && RimeBridge.input().isEmpty()) currentInputConnection?.finishComposingText()
        if (RimeBridge.input().isEmpty()) { resetSyllables(); pinyinEdit = false }
        syncBar()
        if (panel == Panel.CANDIDATES) {
            if (composing) { val c = RimeBridge.candidates(300).toList(); candGrid.notes = annotations(c); candGrid.setItems(c) } else showPanel(Panel.LETTERS)
        }
    }

    private fun resetSyllables() { confirmedSyllables.clear(); lastHanPrefix = 0; lastSyllables = emptyList() }

    /** Follows the preedit ("今天 tian qi") to remember which syllables produced the chosen characters. */
    private fun trackSyllables() {
        val tokens = RimeBridge.preedit().split(' ', '\'').filter { it.isNotEmpty() }
        val han = tokens.takeWhile { t -> t.all { isCjk(it) } }.sumOf { it.length }
        val syl = tokens.dropWhile { t -> t.all { isCjk(it) } }
        if (han > lastHanPrefix) {
            val consumed = han - lastHanPrefix
            confirmedSyllables += lastSyllables.take(consumed)
        }
        lastHanPrefix = han
        lastSyllables = syl
    }

    private val pairs = mapOf(
        "(" to ")", "（" to "）", "[" to "]", "【" to "】", "{" to "}", "«" to "»", "“" to "”", "‘" to "’",
        "《" to "》", "〈" to "〉", "「" to "」", "『" to "』", "〔" to "〕", "„" to "“", "⟨" to "⟩",
    )
    private val closers = pairs.values.toSet()

    /** Absolute selection (start, end) in the field, or null when the editor does not say. */
    private fun selection(): Pair<Int, Int>? {
        val et = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return (et.startOffset + minOf(et.selectionStart, et.selectionEnd)) to (et.startOffset + maxOf(et.selectionStart, et.selectionEnd))
    }

    /** Commits text; `‸` marks where the cursor should land. Handles bracket pairing. */
    private fun commit(text: String, pairable: Boolean = true, syllables: List<String>? = null) {
        val ic = currentInputConnection ?: return
        logChars(text.replace("‸", ""), syllables)
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
        if (idx < 0 || idx >= clean.length) { ic.commitText(clean, 1); return }
        // read the position before the edit: inside a batch edit the editor may report stale values
        val start = selection()?.first
        ic.beginBatchEdit()
        ic.commitText(clean, 1)
        if (start != null) ic.setSelection(start + idx, start + idx)
        else repeat(clean.length - idx) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
        ic.endBatchEdit()
    }

    private fun logChars(text: String, syllables: List<String>?) {
        var si = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val syl = if (syllables != null && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) syllables.getOrNull(si++).orEmpty() else ""
            charLog.addLast(ch to syl)
            i += Character.charCount(cp)
        }
        while (charLog.size > 300) charLog.removeFirst()
        deletedRun.clear()
    }

    /** Called after one character was deleted: keeps the log aligned and collects syllables. */
    private fun onCharDeleted(ch: String) {
        val last = charLog.lastOrNull()
        if (last == null || last.first != ch) { charLog.clear(); deletedRun.clear(); return }
        charLog.removeLast()
        if (last.second.isNotEmpty()) deletedRun.addFirst(last.second) else deletedRun.clear()
    }

    private fun moveBy(n: Int) {
        val ic = currentInputConnection ?: return
        val sel = selection()
        if (sel != null) {
            val p = (sel.second + n).coerceAtLeast(0)
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
        if (!sel.isNullOrEmpty()) { history.push(EditHistory.Op.Delete(sel.toString())); ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(1, 0)?.toString()
        if (before.isEmpty()) return
        // delete an empty bracket pair together
        val last = before.substring(before.offsetByCodePoints(before.length, -1))
        if (prefs.autoPair && pairs[last] == after) { ic.deleteSurroundingText(last.length, 1); return }
        history.push(EditHistory.Op.Delete(last))
        if (Build.VERSION.SDK_INT >= 24) ic.deleteSurroundingTextInCodePoints(1, 0) else ic.deleteSurroundingText(1, 0)
        onCharDeleted(last)
    }

    /**
     * 删除后联想: the deleted characters' syllables (what was typed for them) are looked up again and
     * offered as suggestions; nothing is put back into the input, so typing on simply ignores them.
     * Without a record of the syllables, suggestions follow the text that is left.
     */
    private fun suggestAfterDelete() {
        predictions = emptyList(); predictionLabel = ""
        if (!isZh || panel != Panel.LETTERS) return
        if (prefs.backspaceReselect && deletedRun.isNotEmpty() && RimeEngine.ready) {
            val code = deletedRun.joinToString("")
            predictions = RimeBridge.query(code, 24).filter { it.isNotEmpty() }.distinct()
            predictionLabel = "按原拼音 " + deletedRun.joinToString(" ")
        } else if (prefs.zhPredict) {
            predictions = predictor.suggest(currentInputConnection?.getTextBeforeCursor(20, 0)?.toString().orEmpty())
        }
    }

    @Suppress("unused")
    private fun reselectLastWord(): Boolean {
        if (!prefs.backspaceReselect || !isZh || !RimeEngine.ready || lastCommitInput.isEmpty()) return false
        if (SystemClock.uptimeMillis() - lastCommitAt > 5000) return false
        val ic = currentInputConnection ?: return false
        val word = lastCommitText
        if (ic.getTextBeforeCursor(word.length, 0)?.toString() != word || !ic.getSelectedText(0).isNullOrEmpty()) return false
        val input = lastCommitInput
        lastCommitInput = ""
        ic.deleteSurroundingText(word.length, 0)
        RimeBridge.setInput(input)
        flushRime()
        return true
    }

    /** 上滑清空: drops the pinyin while composing, otherwise the whole field (undoable). */
    private fun clearAll() {
        if (composing) { RimeBridge.clearComposition(); flushRime(); return }
        val ic = currentInputConnection ?: return
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text?.toString()
        if (text != null) {
            if (text.isEmpty()) return
            history.push(EditHistory.Op.Clear(text, et.startOffset + et.selectionEnd))
            ic.beginBatchEdit()
            ic.setSelection(0, text.length)
            ic.commitText("", 1)
            ic.endBatchEdit()
        } else {
            val before = ic.getTextBeforeCursor(10000, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(10000, 0)?.toString().orEmpty()
            if (before.isEmpty() && after.isEmpty()) return
            history.push(EditHistory.Op.Clear(before + after, before.length))
            ic.deleteSurroundingText(before.length, after.length)
        }
        predictions = emptyList()
        syncBar()
        updateAutoCap()
    }

    /** 下滑撤销: undoes the keyboard's own last edit, or asks the app (Ctrl+Z) when there is none. */
    private fun undo() {
        val ic = currentInputConnection ?: return
        when (val op = history.pop()) {
            is EditHistory.Op.Insert -> {
                val before = ic.getTextBeforeCursor(op.text.length, 0)?.toString()
                if (before == op.text) ic.deleteSurroundingText(op.text.length, 0) else sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            }
            is EditHistory.Op.Delete -> ic.commitText(op.text, 1)
            is EditHistory.Op.Clear -> {
                ic.beginBatchEdit()
                ic.commitText(op.text, 1)
                ic.setSelection(op.cursor, op.cursor)
                ic.endBatchEdit()
            }
            null -> sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        }
        predictions = emptyList()
        syncBar()
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
        else if (info?.inputType == InputType.TYPE_NULL) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        else { ic.commitText("\n", 1); history.push(EditHistory.Op.Insert("\n")) }
    }

    /**
     * 句首大写 for Russian and English. Decided from the text before the cursor, because many
     * apps do not ask for sentence capitalization in their input type.
     */
    private fun updateAutoCap() {
        if (!::keyboard.isInitialized) return
        if (shift == 2) return
        val info = currentInputEditorInfo
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val excluded = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val caps = panel == Panel.LETTERS && !isZh && prefs.autoCap && cls == InputType.TYPE_CLASS_TEXT && !excluded && run {
            val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: return@run false
            val t = before.trimEnd(' ')
            t.isEmpty() || t.last() in ".!?…\n。！？" && (before.endsWith(" ") || t.last() == '\n')
        }
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
        if (sinkOn && panel == Panel.LETTERS) {
            val tt = output.text
            if (isZh && output === key.main && tt.length == 1 && tt[0] in 'a'..'z' && rimeKey(tt[0].code)) return
            commitComposingFirst()
            searchType(if (output === key.main && key.isLetter && shift > 0) tt.uppercase() else tt)
            return
        }
        if (panel == Panel.NUM) {
            commit(output.text, pairable = output.text.length > 1)
            history.push(EditHistory.Op.Insert(output.text))
            if (output.text == "=") showCalcResult() else bar.showResult(null)
            return
        }
        predictions = emptyList()
        deletedRun.clear()
        val t = output.text
        val isMain = output === key.main
        if (isZh && panel == Panel.LETTERS) {
            if (isMain && t.length == 1 && (t[0] in 'a'..'z' || (t == "'" && composing))) {
                if (rimeKey(t[0].code)) return
                if (!RimeEngine.ready) { commit(t); return }
            }
            commitComposingFirst()
            val out = smartPunct(t)
            commit(out)
            history.push(EditHistory.Op.Insert(out))
            syncBar()
            return
        }
        var out = t
        if (isMain && key.isLetter && shift > 0) out = t.uppercase()
        commit(out)
        history.push(EditHistory.Op.Insert(out))
        if (isMain && key.isLetter && shift == 1) { shift = 0; autoShift = false; keyboard.invalidate() }
        updateAutoCap()
        if (panel == Panel.LETTERS) syncBar()
    }

    private fun smartPunct(t: String): String {
        if (!prefs.smartPunct) return t
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: return t
        if (before.isNotEmpty() && before[0].isDigit()) return when (t) { "。" -> "."; "：" -> ":"; "，" -> ","; else -> t }
        return t
    }

    override fun onFunction(key: Key) {
        val ic = currentInputConnection
        when (key.fn) {
            "backspace" -> {
                predictions = emptyList()
                when {
                    composing -> rimeKey(RimeBridge.XK_BackSpace)
                    sinkOn -> { if (searchQuery.isNotEmpty()) searchQuery.setLength(searchQuery.offsetByCodePoints(searchQuery.length, -1)); syncBar() }
                    else -> {
                        deleteBackward()
                        updateAutoCap()
                        suggestAfterDelete()
                        syncBar()
                    }
                }
            }
            "space" -> {
                if (composing) { rimeKey(RimeBridge.XK_space); return }
                if (sinkOn) { searchType(" "); return }
                predictions = emptyList()
                val now = SystemClock.uptimeMillis()
                val before = ic?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (prefs.doubleSpacePeriod && !isZh && panel == Panel.LETTERS && now - lastSpaceAt < 600 &&
                    before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic?.deleteSurroundingText(1, 0); commit(". ")
                } else { commit(" "); history.push(EditHistory.Op.Insert(" ")) }
                lastSpaceAt = now
                updateAutoCap()
                syncBar()
            }
            "enter" -> if (composing) rimeKey(RimeBridge.XK_Return)
            else if (translateMode) { if (translateResult.isNotEmpty() && translatedFor == searchQuery.toString()) commitTranslation() }
            else if (emojiSearch) { searchResults.firstOrNull()?.let { onEmoji(it) } }
            else { predictions = emptyList(); performEnter(); updateAutoCap(); syncBar() }
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
            "symbols" -> {
                commitComposingFirst()
                // back to the symbols if they were used a moment ago, otherwise the number keyboard
                val recent = SystemClock.uptimeMillis() - lastSymbolsAt < prefs.symbolMemorySec * 1000L
                if (recent) showPanel(Panel.SYMBOLS) else showPanel(numPanel())
            }
            "sci" -> { prefs.put(Prefs.NUM_MODE, "sci"); showPanel(Panel.MATH) }
            "simple" -> { prefs.put(Prefs.NUM_MODE, "simple"); showPanel(Panel.NUM) }
            "tall" -> { tall = !tall; showPanel(panel) }
            "num" -> showPanel(numPanel())
            "more_symbols" -> showPanel(Panel.SYMBOLS)
            "math" -> { commitComposingFirst(); showPanel(Panel.MATH) }
            "abc" -> { selectMode = false; showPanel(Panel.LETTERS) }
            "collapse" -> showPanel(Panel.LETTERS)
            "reinput" -> { RimeBridge.clearComposition(); flushRime(); showPanel(Panel.LETTERS) }
            "undo" -> undo()
            "lock" -> { symLock = !symLock; symBottom.lockOn = symLock; symBottom.invalidate()
                Toast.makeText(this, if (symLock) "已锁定，可以连续输入符号" else "已解锁，点一个符号就回到字母键盘", Toast.LENGTH_SHORT).show() }
            "left" -> caretStep(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> caretStep(KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> caretStep(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> caretStep(KeyEvent.KEYCODE_DPAD_DOWN)
            "word_left" -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or shiftMeta())
            "word_right" -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON or shiftMeta())
            "home" -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, shiftMeta())
            "end" -> sendKey(KeyEvent.KEYCODE_MOVE_END, shiftMeta())
            "select_all" -> ic?.performContextMenuAction(android.R.id.selectAll)
            "copy" -> { ic?.performContextMenuAction(android.R.id.copy); selectMode = false }
            "cut" -> { ic?.performContextMenuAction(android.R.id.cut); selectMode = false }
            "paste" -> ic?.performContextMenuAction(android.R.id.paste)
            "next_slot" -> nextSlot()
            "clear_clips" -> { clips.clearUnpinned(); refreshClips() }
        }
    }

    private fun shiftMeta() = if (selectMode) KeyEvent.META_SHIFT_ON else 0

    override fun longOptions(key: Key): List<Output> = longOptionRows(key).flatten()

    /**
     * 长按选项, grouped: symbols (the flick characters, the digit, extra punctuation) in one
     * row right above the key, letters (both cases, accented forms) in another. Short lists
     * stay on one row, symbols first.
     */
    override fun longOptionRows(key: Key): List<List<Output>> {
        key.fn?.let { return listOf(fnOptions(it, key)) }
        val m = key.main ?: return emptyList()
        if (panel == Panel.MATH) {
            val list = listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT).mapNotNull { key.flicks[it] }
            return if (list.isEmpty()) emptyList() else listOf(list + m)
        }
        val symbols = mutableListOf<Output>()
        val letters = mutableListOf<Output>()
        if (prefs.lpHints) listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT).forEach { d -> key.flicks[d]?.let { symbols += it } }
        if (prefs.lpDigits && panel == Panel.LETTERS) {
            val row0 = layout(curLang).rows.firstOrNull()
            val col = row0?.indexOf(key) ?: -1
            if (col in 0..9) symbols += Output.of(((col + 1) % 10).toString())
        }
        if (prefs.lpCase && key.isLetter) {
            val up = m.text.uppercase(); val low = m.text.lowercase()
            if (shift > 0) { letters += Output.of(low); letters += Output.of(up) } else { letters += Output.of(up); letters += Output.of(low) }
        }
        if (curLang == "ru" && m.text.length == 1 && m.text[0] in vowels) letters += Output.of(m.text + "́")
        for (o in key.long) if (o.text.firstOrNull()?.isLetter() == true) letters += o else symbols += o
        val s = symbols.distinctBy { it.text }
        val l = letters.distinctBy { it.text }.filter { o -> s.none { it.text == o.text } }
        val all = s + l
        if (all.isEmpty() || (all.size == 1 && all[0].text == m.text)) return emptyList()
        return if (s.isEmpty() || l.isEmpty() || all.size <= 5) listOf(all) else listOf(s, l)
    }

    private fun fnOptions(fn: String, key: Key): List<Output> = when (fn) {
        "language" -> prefs.languages.map { Output(layout(it).label, "§lang:$it") } +
            listOf(Output("系统切换", "§picker"), Output("设置", "§settings"))
        "symbols" -> listOf(Output("数字·标准", "§num"), Output("符号", "§sym"), Output("数字·学术", "§科学"), Output("文字编辑", "§cursor"), Output("剪贴板", "§clip"))
        "more_symbols" -> listOf(Output("全部符号", "§sym"), Output("数学", "§math"))
        "sci" -> listOf(Output("全部符号", "§sym"))
        "enter" -> listOf(Output("换行", "§newline"), Output(enterActionName(), "§action"))
        "shift" -> listOf(Output("大写锁定", "§caps"))
        else -> key.long.map { Output(it.label, "§" + it.text) }
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
            t == "§num" -> onFunction(Key(fn = "simple"))
            t == "§sym" || t == "§全部符号" -> showPanel(Panel.SYMBOLS)
            t == "§科学" -> onFunction(Key(fn = "sci"))
            t == "§math" || t == "§数学" -> showPanel(Panel.MATH)
            t == "§cursor" -> showPanel(Panel.CURSOR)
            t == "§clip" -> showPanel(Panel.CLIPBOARD)
            t == "§newline" -> commit("\n")
            t == "§action" -> performEnter()
            t == "§caps" -> { shift = 2; keyboard.invalidate() }
        }
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        var moved = false
        repeat(abs(dx)) { moved = caretStep(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) || moved }
        repeat(abs(dy)) { moved = caretStep(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN) || moved }
        if (moved) haptics.play(HapticEvent.CURSOR)
    }

    /**
     * Moves the caret one character (or line) inside the field. At the start or end of the text it
     * stops there: arrow keys sent past the edge would move the focus to other parts of the app.
     * In selection mode the moving end of the selection is extended. Returns false at an edge.
     */
    private fun caretStep(code: Int): Boolean {
        val ic = currentInputConnection ?: return false
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text?.toString()
        if (et == null || text == null) { sendKey(code, shiftMeta()); return true }
        val anchor = et.selectionStart.coerceIn(0, text.length)
        val caret = et.selectionEnd.coerceIn(0, text.length)
        val bi = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        fun prev(i: Int) = if (i <= 0) 0 else bi.preceding(i).let { if (it == android.icu.text.BreakIterator.DONE) 0 else it }
        fun next(i: Int) = if (i >= text.length) text.length else bi.following(i).let { if (it == android.icu.text.BreakIterator.DONE) text.length else it }
        val target: Int = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!selectMode && anchor != caret) minOf(anchor, caret) else prev(caret)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!selectMode && anchor != caret) maxOf(anchor, caret) else next(caret)
            KeyEvent.KEYCODE_DPAD_UP -> {
                // a line break above: let the editor move by line; otherwise go to the start
                if (caret > 0 && text.lastIndexOf('\n', caret - 1) >= 0) { sendKey(code, shiftMeta()); return true }
                0
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (text.indexOf('\n', caret) >= 0) { sendKey(code, shiftMeta()); return true }
                text.length
            }
            else -> caret
        }
        if (target == caret && (selectMode || anchor == caret)) return false
        val base = et.startOffset
        if (selectMode) ic.setSelection(base + anchor, base + target) else ic.setSelection(base + target, base + target)
        return true
    }

    override fun onCursorMode(active: Boolean) {
        if (active) { commitComposingFirst(); haptics.play(HapticEvent.GESTURE) }
    }

    override fun onSwitchLanguage(direction: Int): String? {
        // 数字和符号之间左右滑空格切换, not the language
        if (panel == Panel.NUM) { showPanel(Panel.SYMBOLS); return null }
        if (panel == Panel.SYMBOLS) { showPanel(numPanel()); return null }
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
        predictions = emptyList()
        currentInputEditorInfo?.packageName?.let { if (prefs.perAppLanguage) prefs.setAppLanguage(it, id) }
        if (panel != Panel.LETTERS) showPanel(Panel.LETTERS) else { keyboard.setRows(lettersRows()); syncBar() }
        updateAutoCap()
    }

    override fun onBackspaceRepeat() {
        haptics.play(HapticEvent.REPEAT)
        if (composing) rimeKey(RimeBridge.XK_BackSpace) else deleteBackward()
    }

    override fun onBackspaceGesture(action: String) {
        if (action == "clear") clearAll() else undo()
    }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    override fun onPress(key: Key) {
        haptics.play(when (key.fn) {
            null -> HapticEvent.LETTER
            "space" -> HapticEvent.SPACE
            "backspace" -> HapticEvent.BACKSPACE
            "enter" -> HapticEvent.ENTER
            else -> HapticEvent.FUNCTION
        })
        if (prefs.sound) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val fx = when (key.fn) {
                "backspace" -> AudioManager.FX_KEYPRESS_DELETE
                "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
                "enter" -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            val vol = prefs.soundVolume
            haptics.worker.post { am.playSoundEffect(fx, vol) }
        }
    }

    // ---- math ---------------------------------------------------------------------------

    private fun mathOut(o: Output) {
        val text = if (prefs.mathFormat == "tex") o.tex else o.text
        commit(text, pairable = false)
        bar.showResult(null)
        if (o.label == "=" && prefs.mathFormat == "uni") showCalcResult()
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

    private fun panelForTool(id: String) = when (id) {
        "mic" -> Panel.VOICE
        "ai" -> Panel.AI
        "cursor" -> Panel.CURSOR
        "clipboard" -> Panel.CLIPBOARD
        "quick" -> Panel.QUICK
        else -> null
    }

    override fun onTool(id: String) {
        val target = panelForTool(id)
        // a second tap on the same button closes its panel
        if (target != null && panel == target) { showPanel(Panel.LETTERS); return }
        if (id == "emoji" && panel == Panel.EMOJI) { showPanel(Panel.LETTERS); return }
        when (id) {
            "cursor", "clipboard", "quick", "mic", "ai" -> showPanel(target!!)
            "emoji" -> showPanel(Panel.EMOJI)
            "symbols" -> showPanel(if (panel == Panel.SYMBOLS) Panel.LETTERS else Panel.SYMBOLS)
            "math" -> { commitComposingFirst(); showPanel(if (panel == Panel.MATH) Panel.LETTERS else Panel.MATH) }
            "undo" -> undo()
            "settings" -> openSettings()
            "translate" -> if (translateMode) stopTranslate() else startTranslate()
        }
    }

    override fun onCandidate(index: Int) {
        if (index < 0 && emojiSearch && !composing) {
            searchResults.getOrNull(-1 - index)?.let { onEmoji(it) }
            return
        }
        if (index < 0) {
            // an association: commit it and suggest what follows
            val word = predictions.getOrNull(-1 - index) ?: return
            val run = deletedRun.toList()
            commit(word, pairable = false, syllables = run.takeIf { it.size == word.count { c -> isCjk(c) } })
            history.push(EditHistory.Op.Insert(word))
            predictions = predictor.suggest(currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: word)
            predictionLabel = ""
            syncBar()
            return
        }
        if (!RimeEngine.ready) return
        trackSyllables()
        inputBeforeKey = RimeBridge.input()
        RimeBridge.selectCandidateGlobal(index)
        flushRime()
    }

    override fun candidateOptions(index: Int): List<Output> {
        val word = RimeBridge.candidates(index + 1).getOrNull(index) ?: return emptyList()
        return listOf(Output("取消", "§cancel"), Output("删除「$word」的学习记录", "§forget"))
    }

    override fun onCandidateOption(index: Int, option: Output) {
        if (option.text != "§forget") return
        val ok = RimeBridge.deleteCandidate(index)
        flushRime()
        Toast.makeText(this, if (ok) "已删除，下次不会再把它排到前面" else "这个词没有学习记录", Toast.LENGTH_SHORT).show()
    }

    override fun onFeedback(kind: String) {
        haptics.play(when (kind) { "popup" -> HapticEvent.POPUP; "press" -> HapticEvent.LETTER; "cancel" -> HapticEvent.OPTION; else -> HapticEvent.GESTURE })
    }

    override fun onNumMode(mode: String) = onFunction(Key(fn = if (mode == "sci") "sci" else "simple"))

    /** 点拼音改拼音: maps a tap in the preedit to a position in the typed letters and moves Rime's caret there. */
    override fun onPreeditTap(index: Int) {
        if (!composing) return
        val pre = RimeBridge.preedit()
        val input = RimeBridge.input()
        var j = 0
        for (k in 0 until minOf(index, pre.length)) {
            val ch = pre[k]
            if (isCjk(ch)) return
            if (j < input.length && (ch == input[j] || (ch == 'u' && input[j] == 'v') || (ch == 'ü' && input[j] == 'v'))) j++
        }
        RimeBridge.setCaretPos(j)
        haptics.play(HapticEvent.CURSOR)
        pinyinEdit = true
        if (panel == Panel.CANDIDATES) showPanel(Panel.LETTERS) else syncBar()
    }

    override fun onEditCaret(pos: Int) {
        if (!composing) return
        RimeBridge.setCaretPos(pos)
        haptics.play(HapticEvent.CURSOR)
    }

    override fun onEditDone() { pinyinEdit = false; haptics.play(HapticEvent.FUNCTION); syncBar() }

    /** Where syllables start in the typed letters, read from the pinyin part of the preedit. */
    private fun syllableGaps(): Set<Int> {
        val input = RimeBridge.input()
        val tokens = RimeBridge.preedit().split(' ', '\'').filter { it.isNotEmpty() }
        val syl = tokens.dropWhile { t -> t.any { isCjk(it) } }.map { it.filter { c -> !isCjk(c) } }
        val gaps = mutableSetOf<Int>()
        var p = input.length - syl.sumOf { it.length } - input.count { it == '\'' }
        if (p < 0) return emptySet()
        for (t in syl) {
            if (p > 0) gaps += p
            p += t.length
            if (p < input.length && input[p] == '\'') p++
        }
        return gaps
    }

    /** Caret position in the preedit when it is not at the end, else -1. */
    private fun caretInPreedit(): Int {
        val pre = RimeBridge.preedit()
        val bytes = RimeBridge.cursor()
        val utf8 = pre.toByteArray(Charsets.UTF_8)
        if (bytes <= 0 || bytes >= utf8.size) return -1
        return String(utf8, 0, bytes, Charsets.UTF_8).length
    }

    /**
     * Small notes after candidates: the proofreading mark of a typo correction ("p→o", "no⇄on"),
     * rime-ice's 错音提示, or the reading of rare characters (生僻字注音).
     */
    private fun annotations(cands: List<String>): List<String> {
        val comments = RimeBridge.candidateComments(cands.size).toList()
        val common = if (prefs.rareAnnotate) commonChars ?: runCatching {
            assets.open("data/common_chars.txt").bufferedReader().readText().toSet()
        }.getOrDefault(emptySet()).also { commonChars = it } else emptySet()
        return cands.mapIndexed { i, w ->
            val c = comments.getOrNull(i).orEmpty()
            val m = Regex("^〔kk:([a-z']+)>([a-z']+)〕$").find(c)
            when {
                m != null -> if (prefs.typoMark) typoNote(m.groupValues[1], m.groupValues[2]) else ""
                c.isNotEmpty() && !c.startsWith("［") -> c.take(14)
                prefs.rareAnnotate && w.length in 1..4 && w.all { isCjk(it) } && w.any { it !in common } -> tones.convert(w, emptyList())
                else -> ""
            }
        }
    }

    private fun typoNote(typed: String, want: String): String {
        val i = typed.indices.firstOrNull { typed[it] != want.getOrNull(it) } ?: return ""
        return if (i + 1 < typed.length && typed[i] == want[i + 1] && typed[i + 1] == want[i]) "${typed[i]}${typed[i + 1]}⇄${want[i]}${want[i + 1]}"
        else "${typed[i]}→${want[i]}"
    }

    private val glyphPaint = android.graphics.Paint()

    private fun loadEmoji() {
        runCatching { emojiData.load() }
        // only emoji the system font can draw (newer ones show as boxes on older phones)
        emojiGroups = emojiData.groups.map { (g, l) -> g to l.filter { glyphPaint.hasGlyph(it) } }.filter { it.second.isNotEmpty() }
        kaomoji = runCatching { assets.open("data/kaomoji.txt").bufferedReader().readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    /** 自动更新: at most once a week, in the background, when the keyboard starts. */
    private fun maybeUpdateEmoji(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (!prefs.emojiAutoUpdate || now - prefs.sp.getLong(Prefs.EMOJI_CHECKED, 0L) < 7L * 24 * 3600 * 1000)) return
        prefs.sp.edit().putLong(Prefs.EMOJI_CHECKED, now).apply()
        val current = emojiData.version
        Thread {
            val v = runCatching { EmojiData.update(this, current) }.getOrNull()
            if (v != null) android.os.Handler(mainLooper).post {
                loadEmoji()
                if (::emojiPanel.isInitialized && panel == Panel.EMOJI) refreshEmoji()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun buildEmojiPanel() {
        emojiPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        emojiRail = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; columns = 1; rowHeightDp = 36f; textSp = 13.5f; cellStyle = GridPanel.CellStyle.RAIL
            // the first item is 查找, the rest are tabs
            onItem = { i -> if (i == 0) startEmojiSearch() else { emojiGroup = i - 1; refreshEmoji() } }
            onFeedback = { haptics.play(HapticEvent.FUNCTION) }
        }
        emojiCells = GridPanel(this, prefs).apply {
            theme = this@KkImeService.theme; columns = 8; rowHeightDp = 40f; textSp = 22f; cellStyle = GridPanel.CellStyle.PLAIN
            emptyText = "发过的表情会出现在这里"
            onItem = { i -> emojiShown.getOrNull(i)?.let { onEmoji(it) } }
            onFeedback = { k -> haptics.play(if (k == "popup") HapticEvent.POPUP else HapticEvent.LETTER) }
            overlay = this@KkImeService.overlay
            // 长按选肤色: the emoji and its forms, six to a row (one row per man/woman form)
            longOptionRows = { i -> emojiShown.getOrNull(i)?.let { variantRows(it) }.orEmpty() }
            onLongOption = { i, o -> emojiShown.getOrNull(i)?.let { shown -> chooseVariant(shown, o.text) } }
        }
        top.addView(emojiRail, LinearLayout.LayoutParams(dp(64f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(emojiCells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val bottom = newKeyboard()
        bottom.setRows(listOf(listOf(
            Key(fn = "abc", label = "ABC", width = 1.5f), Key(fn = "space", width = 4f),
            Key(fn = "backspace", width = 1.5f), Key(fn = "enter", width = 1.5f),
        )))
        emojiPanel.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        emojiPanel.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshEmoji() {
        val kaoTab = emojiGroups.size + 1
        emojiGroup = emojiGroup.coerceIn(0, kaoTab)
        emojiRail.selectedIndex = emojiGroup + 1
        emojiRail.setItems(listOf("⌕ 查找", "最近") + emojiGroups.map { it.first } + "颜文字", resetScroll = false)
        val kao = emojiGroup == kaoTab
        emojiShown = when {
            emojiGroup == 0 -> recentEmoji.toList()
            kao -> (recentKao + kaomoji).distinct()
            else -> emojiGroups.getOrNull(emojiGroup - 1)?.second.orEmpty()
        }
        // kaomoji are wide: three to a row, in text size
        emojiCells.columns = if (kao) 3 else 8
        emojiCells.textSp = if (kao) 14f else 22f
        emojiCells.rowHeightDp = if (kao) 44f else 40f
        // cells with skin tones or man/woman forms get a corner mark
        emojiCells.marks = if (kao) emptySet() else emojiShown.indices.filter { emojiData.variants.containsKey(emojiBase(emojiShown[it])) }.toSet()
        emojiCells.setItems(emojiShown)
    }

    private fun onEmoji(e: String) {
        commitComposingFirst()
        commit(e, pairable = false)
        history.push(EditHistory.Op.Insert(e))
        if (e in kaomoji || recentKao.contains(e)) {
            recentKao.remove(e); recentKao.add(0, e)
            while (recentKao.size > 12) recentKao.removeAt(recentKao.size - 1)
            prefs.sp.edit().putString("kao_recent", recentKao.joinToString("\u0001")).apply()
            return
        }
        recentEmoji.remove(e); recentEmoji.add(0, e)
        while (recentEmoji.size > 32) recentEmoji.removeAt(recentEmoji.size - 1)
        prefs.sp.edit().putString("emoji_recent", recentEmoji.joinToString(" ")).apply()
    }

    /** The base emoji a shown one belongs to (a chosen skin tone maps back to its base). */
    private fun emojiBase(e: String): String = if (emojiData.variants.containsKey(e)) e
        else toneChoice.entries.firstOrNull { it.value == e }?.key
        ?: emojiData.variants.entries.firstOrNull { e in it.value }?.key ?: e

    private fun variantRows(shown: String): List<List<Output>> {
        val base = emojiBase(shown)
        val forms = emojiData.variants[base].orEmpty().filter { glyphPaint.hasGlyph(it) }
        if (forms.isEmpty()) return emptyList()
        return (listOf(base) + forms).chunked(6).map { row -> row.map { Output.of(it) } }
    }

    /** The chosen form is sent once; the grid keeps showing the default yellow. */
    private fun chooseVariant(@Suppress("UNUSED_PARAMETER") shown: String, v: String) {
        onEmoji(v)
        refreshEmojiKeepScroll()
    }

    /** Re-reads the current tab without moving the grid (after a tone was chosen). */
    private fun refreshEmojiKeepScroll() {
        if (emojiGroup in 1..emojiGroups.size) {
            emojiShown = emojiGroups[emojiGroup - 1].second
            emojiCells.setItems(emojiShown, resetScroll = false)
        } else if (emojiGroup == 0) {
            emojiShown = recentEmoji.toList()
            emojiCells.setItems(emojiShown, resetScroll = false)
        }
    }

    // ---- 翻译 ----------------------------------------------------------------------------

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val translateSvc by lazy { com.kk.shurufa.online.TranslateService(prefs) }
    private var translateFrom = "auto"
    private var translateFromSelection = false

    private fun buildTranslateStrip() {
        translateStrip = com.kk.shurufa.keyboard.TranslateStrip(this, prefs).apply {
            theme = this@KkImeService.theme
            visibility = View.GONE
            languages = com.kk.shurufa.online.TranslateService.LANGS
            onFeedback = { haptics.play(HapticEvent.FUNCTION) }
            onClose = { stopTranslate() }
            onCommit = { commitTranslation() }
            onSwap = {
                val src = if (translateFrom == "auto") com.kk.shurufa.online.TranslateService.guess(searchQuery.toString()) else translateFrom
                translateFrom = translateTo; translateTo = src
                // the previous result becomes the text to translate back
                if (translateResult.isNotEmpty() && !translateFromSelection) { searchQuery.setLength(0); searchQuery.append(translateResult) }
                translatedFor = ""; syncTranslate()
            }
            onPick = { which, lang ->
                if (which == "from") translateFrom = lang else { translateTo = lang; prefs.put(Prefs.TRANSLATE_TARGET, lang) }
                translatedFor = ""; syncTranslate()
            }
            onEngine = {
                val engines = listOf("mlkit") + com.kk.shurufa.online.Llm.configuredIds(prefs)
                val next = engines[(engines.indexOf(translateSvc.engine) + 1) % engines.size]
                prefs.put(Prefs.TRANSLATE_ENGINE, next)
                if (engines.size == 1) Toast.makeText(this@KkImeService, "在 设置 → 联网功能 里添加 AI 服务后，可以用它来翻译", Toast.LENGTH_SHORT).show()
                translatedFor = ""; syncTranslate()
            }
        }
    }

    /** Opens the strip above the toolbar; the selection, if any, is translated right away. */
    private fun startTranslate() {
        commitComposingFirst()
        emojiSearch = false
        translateMode = true
        translateTo = prefs.translateTarget
        translateFrom = "auto"
        searchQuery.setLength(0)
        val sel = currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        translateFromSelection = sel != null
        sel?.let { searchQuery.append(it) }
        translateResult = ""; translatedFor = ""
        haptics.play(HapticEvent.FUNCTION)
        showPanel(Panel.LETTERS)
        translateStrip.visibility = View.VISIBLE
        if (prefs.animations) {
            translateStrip.alpha = 0f; translateStrip.translationY = dp(24f)
            translateStrip.animate().alpha(1f).translationY(0f).setDuration((200 * prefs.animSpeed).toLong())
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).withLayer().start()
        }
        syncTranslate()
    }

    private fun stopTranslate() {
        if (!translateMode) return
        translateMode = false
        translateSvc.cancel()
        if (composing) RimeBridge.clearComposition()
        searchQuery.setLength(0)
        handler.removeCallbacks(translateRun)
        translateStrip.visibility = View.GONE
        showPanel(Panel.LETTERS)
    }

    /** Updates the strip; a changed text is translated after a short pause in typing. */
    private fun syncTranslate() {
        if (!translateMode) return
        val q = searchQuery.toString()
        val st = translateStrip
        val src = if (translateFrom == "auto") com.kk.shurufa.online.TranslateService.guess(q) else translateFrom
        if (src == translateTo) translateTo = if (src == "zh") "en" else "zh"
        st.from = translateFrom; st.to = translateTo
        st.fromName = if (translateFrom == "auto") (if (q.isBlank()) "自动" else "自动·" + com.kk.shurufa.online.TranslateService.name(src)) else com.kk.shurufa.online.TranslateService.name(translateFrom)
        st.toName = com.kk.shurufa.online.TranslateService.name(translateTo)
        st.engineName = translateSvc.engineName()
        st.source = q; st.sourceIsSelection = translateFromSelection
        val fresh = translatedFor == q
        if (!fresh) { st.result = ""; st.busy = q.isNotBlank() }
        if (q.isBlank()) st.status = ""
        if (!fresh && q.isNotBlank()) { handler.removeCallbacks(translateRun); handler.postDelayed(translateRun, if (translateFromSelection) 0 else 400) }
        st.refresh()
    }

    private fun runTranslate() {
        val q = searchQuery.toString()
        if (!translateMode || q.isBlank()) return
        translateSvc.translate(q, translateFrom, translateTo, onPartial = { t ->
            if (searchQuery.toString() == q) { translateStrip.result = t; translateStrip.refresh() }
        }) { r ->
            if (!translateMode || searchQuery.toString() != q) return@translate
            translatedFor = q
            r.onSuccess { translateResult = it; translateStrip.result = it; translateStrip.status = "" }
             .onFailure { translateResult = ""; translateStrip.result = ""; translateStrip.status = it.message ?: "翻译失败" }
            translateStrip.busy = false
            translateStrip.refresh()
        }
    }

    private fun commitTranslation() {
        val t = translateResult
        if (t.isEmpty()) return
        stopTranslate()
        commit(t, pairable = false)
        history.push(EditHistory.Op.Insert(t))
    }

    // ---- AI 改写 --------------------------------------------------------------------------

    private var aiAction: com.kk.shurufa.online.AiWriter.Action? = null
    @Volatile private var aiRun = 0

    private fun buildAiPanel() {
        aiPanel = com.kk.shurufa.keyboard.AiPanel(this, prefs).apply {
            theme = this@KkImeService.theme
            actions = com.kk.shurufa.online.AiWriter.ACTIONS.map { com.kk.shurufa.keyboard.AiPanel.Act(it.id, it.title, it.group) }
            onFeedback = { haptics.play(HapticEvent.FUNCTION) }
            onAction = { id -> runAi(id) }
            onButton = { b ->
                when (b) {
                    "setup" -> openSettings("online")
                    "replace" -> applyAi(true)
                    "insert" -> applyAi(false)
                    "retry" -> aiAction?.let { runAi(it.id) }
                    "copy" -> {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("AI", aiResult))
                        Toast.makeText(this@KkImeService, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    "provider" -> {
                        val ids = com.kk.shurufa.online.Llm.configuredIds(prefs)
                        if (ids.size > 1) { prefs.put(Prefs.AI_PROVIDER, ids[(ids.indexOf(aiProvider()) + 1) % ids.size]); syncAiHeader() }
                        else openSettings("online")
                    }
                }
            }
        }
    }

    private fun aiProvider(): String {
        val ids = com.kk.shurufa.online.Llm.configuredIds(prefs)
        return prefs.aiProvider.takeIf { it in ids } ?: ids.firstOrNull() ?: prefs.aiProvider
    }

    private fun syncAiHeader() {
        val id = aiProvider()
        aiPanel.needsSetup = com.kk.shurufa.online.Llm.configuredIds(prefs).isEmpty()
        aiPanel.provider = com.kk.shurufa.online.Llm.provider(id).title + " · " + com.kk.shurufa.online.Llm.model(prefs, id).removePrefix("claude-")
        aiPanel.invalidate()
    }

    /** The text the AI works on: the selection, or else everything in the field. */
    private fun aiSource(): String {
        val ic = currentInputConnection ?: return ""
        ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { aiFromSelection = true; return it }
        aiFromSelection = false
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        return et?.text?.toString() ?: (ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty() + ic.getTextAfterCursor(4000, 0)?.toString().orEmpty())
    }

    private fun onAiOpened() {
        commitComposingFirst()
        val src = aiSource()
        aiPanel.source = src.ifBlank { "（输入框是空的）" }
        aiPanel.sourceLabel = if (aiFromSelection) "选中" else "全文"
        aiPanel.running = null; aiPanel.status = ""
        aiPanel.setResult("", null)
        aiResult = ""; aiAction = null
        syncAiHeader()
    }

    private fun runAi(id: String) {
        val action = com.kk.shurufa.online.AiWriter.ACTIONS.firstOrNull { it.id == id } ?: return
        val src = aiSource()
        if (src.isBlank()) { aiPanel.status = "输入框里还没有文字"; aiPanel.setResult("", null); return }
        val run = ++aiRun
        aiAction = action
        aiPanel.running = id; aiPanel.doneTitle = action.title; aiPanel.status = ""
        aiPanel.setResult("", null)
        val provider = aiProvider()
        Thread {
            val r = runCatching {
                com.kk.shurufa.online.Llm.complete(prefs, provider, com.kk.shurufa.online.AiWriter.SYSTEM, com.kk.shurufa.online.AiWriter.prompt(action, src),
                    onText = { t -> handler.post { if (run == aiRun) aiPanel.setResult(t, null) } }, cancelled = { run != aiRun })
            }
            handler.post {
                if (run != aiRun) return@post
                aiPanel.running = null
                r.onSuccess { aiResult = it; aiPanel.setResult(it, if (action.diff) src else null) }
                 .onFailure { aiResult = ""; aiPanel.setResult("", null); aiPanel.status = it.message ?: "出错了" }
                haptics.play(HapticEvent.GESTURE)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun applyAi(replace: Boolean) {
        val t = aiResult
        val ic = currentInputConnection ?: return
        if (t.isEmpty()) return
        aiRun++
        ic.beginBatchEdit()
        if (replace && !aiFromSelection) {
            // the whole field is replaced (undoable with 下滑撤销 on backspace)
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            val all = et?.text?.toString().orEmpty()
            history.push(EditHistory.Op.Clear(all, (et?.startOffset ?: 0) + (et?.selectionEnd ?: 0)))
            ic.setSelection(0, all.length)
        } else if (!replace) {
            selection()?.let { ic.setSelection(it.second, it.second) }
        }
        ic.commitText(t, 1)
        ic.endBatchEdit()
        showPanel(Panel.LETTERS)
    }

    // ---- 语音输入 ------------------------------------------------------------------------

    /** 转文字 or 翻译 (each sentence translated before it goes in). */
    private var voiceTranslate = false
    private var voiceSessionText = false
    private var lastVoiceInsert = ""

    private fun buildVoicePanel() {
        voiceView = com.kk.shurufa.keyboard.VoicePanel(this, prefs).apply {
            theme = this@KkImeService.theme
            onFeedback = { haptics.play(HapticEvent.FUNCTION) }
            onMic = { onMicButton() }
            onCancel = { voice?.cancel(); currentInputConnection?.finishComposingText() }
            onLanguage = {
                val order = listOf("zh", "en", "ru", "auto")
                voiceLangPick = order[(order.indexOf(voiceLang()) + 1) % order.size]
                syncVoiceChips()
                // switching between the Chinese and the foreign model needs a restart
                if (voice?.state == com.kk.shurufa.voice.VoiceEngine.State.LISTENING) { voice?.cancel(); onMicButton() }
            }
            onMode = { voiceTranslate = !voiceTranslate; syncVoiceChips() }
            onUndo = { undoVoice() }
            onDelete = { repeat -> if (!repeat) haptics.play(HapticEvent.BACKSPACE) else haptics.play(HapticEvent.REPEAT); deleteBackward() }
        }
        voicePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voicePanel.addView(voiceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun voiceLang(): String = voiceLangPick ?: prefs.voiceLang.let { if (it == "follow") (if (isZh) "zh" else curLang.takeIf { l -> l == "ru" || l == "en" } ?: "zh") else it }
    private fun voiceLangName(l: String) = when (l) { "zh" -> "中文"; "en" -> "English"; "ru" -> "Русский"; else -> "自动" }

    private fun syncVoiceChips() {
        voiceView.language = voiceLangName(voiceLang())
        voiceView.translating = voiceTranslate
        voiceView.mode = if (voiceTranslate) "译成" + com.kk.shurufa.online.TranslateService.name(prefs.translateTarget) else "转文字"
    }

    private fun voiceEngine(): com.kk.shurufa.voice.VoiceEngine = voice ?: com.kk.shurufa.voice.VoiceEngine(this, prefs).also { e ->
        voice = e
        e.listener = object : com.kk.shurufa.voice.VoiceEngine.Listener {
            override fun onLevel(level: Float) = voiceView.setLevel(level)
            override fun onPartial(text: String) {
                voiceView.partial = text
                // 流式: the sentence grows in the field as it is spoken
                if (!voiceTranslate) currentInputConnection?.setComposingText(previewVoice(text), 1)
            }
            override fun onSegment(text: String, pauseMs: Long) = commitVoice(text, pauseMs)
            override fun onState(state: com.kk.shurufa.voice.VoiceEngine.State, detail: String) {
                voiceView.look = when (state) {
                    com.kk.shurufa.voice.VoiceEngine.State.LISTENING -> com.kk.shurufa.keyboard.VoicePanel.Look.LISTENING
                    com.kk.shurufa.voice.VoiceEngine.State.LOADING -> com.kk.shurufa.keyboard.VoicePanel.Look.LOADING
                    com.kk.shurufa.voice.VoiceEngine.State.RECOGNIZING -> com.kk.shurufa.keyboard.VoicePanel.Look.WORKING
                    else -> com.kk.shurufa.keyboard.VoicePanel.Look.READY
                }
                voiceView.status = when (state) {
                    com.kk.shurufa.voice.VoiceEngine.State.LISTENING -> "正在听…停顿 ${prefs.voiceAutoStopSec} 秒自动结束"
                    com.kk.shurufa.voice.VoiceEngine.State.LOADING -> "正在加载模型…"
                    com.kk.shurufa.voice.VoiceEngine.State.RECOGNIZING -> "识别中…"
                    else -> detail.ifEmpty { if (voiceSessionText) "点按继续说" else "点按开始说话" }
                }
                if (state == com.kk.shurufa.voice.VoiceEngine.State.IDLE || state == com.kk.shurufa.voice.VoiceEngine.State.ERROR) {
                    voiceView.partial = ""
                    currentInputConnection?.finishComposingText()
                    // 说完回到键盘 (when something was said and the setting is on)
                    if (state == com.kk.shurufa.voice.VoiceEngine.State.IDLE && prefs.voiceReturn && voiceSessionText && panel == Panel.VOICE)
                        handler.postDelayed({ if (panel == Panel.VOICE && voice?.state == com.kk.shurufa.voice.VoiceEngine.State.IDLE) showPanel(Panel.LETTERS) }, 350)
                }
                if (state == com.kk.shurufa.voice.VoiceEngine.State.LISTENING) haptics.play(HapticEvent.GESTURE)
            }
        }
    }

    /** Opening the panel starts listening right away, when it can. */
    private fun onVoiceOpened() {
        syncVoiceChips()
        voiceView.partial = ""
        voiceView.canUndo = false
        voiceSessionText = false
        val lang = voiceLang()
        when {
            !com.kk.shurufa.voice.PermissionActivity.granted(this) -> {
                voiceView.look = com.kk.shurufa.keyboard.VoicePanel.Look.READY
                voiceView.status = "需要麦克风权限，点麦克风去允许"
            }
            !voiceEngine().modelReady(lang) -> {
                voiceView.look = com.kk.shurufa.keyboard.VoicePanel.Look.NEED_MODEL
                voiceView.status = if (voiceEngine().usesWhisper(lang)) "先下载外文模型，点麦克风去设置" else "先下载中文模型，点麦克风去设置"
            }
            else -> { commitComposingFirst(); voiceEngine().start(lang) }
        }
    }

    private fun onMicButton() {
        val e = voiceEngine()
        val lang = voiceLang()
        when {
            !com.kk.shurufa.voice.PermissionActivity.granted(this) -> com.kk.shurufa.voice.PermissionActivity.request(this)
            !e.modelReady(lang) -> openSettings("voice")
            e.state == com.kk.shurufa.voice.VoiceEngine.State.LISTENING -> e.stop()
            e.state == com.kk.shurufa.voice.VoiceEngine.State.LOADING || e.state == com.kk.shurufa.voice.VoiceEngine.State.RECOGNIZING -> {}
            else -> { commitComposingFirst(); e.start(lang) }
        }
    }

    private fun textBefore(n: Int) = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    /** What a growing sentence would look like once committed (shown as composing text). */
    private fun previewVoice(text: String): String = com.kk.shurufa.voice.VoiceText.format(
        text, textBefore(4), voiceSessionText, prefs.voicePunct, prefs.voicePangu, prefs.voiceFillers).insert

    private fun commitVoice(text: String, pauseMs: Long) {
        val ic = currentInputConnection ?: return
        if (text.isBlank()) { ic.finishComposingText(); return }
        if (voiceTranslate) {
            voiceView.partial = text
            translateSvc.translate(text, voiceLang().let { if (it == "auto") "auto" else it }, prefs.translateTarget) { r ->
                r.onSuccess { insertVoice(it, pauseMs) }.onFailure { voiceView.status = it.message ?: "翻译失败" }
            }
            return
        }
        insertVoice(text, pauseMs)
    }

    private fun insertVoice(text: String, pauseMs: Long) {
        val ic = currentInputConnection ?: return
        // the composing preview is replaced by the final text
        ic.setComposingText("", 1)
        val edit = com.kk.shurufa.voice.VoiceText.format(text, textBefore(4), voiceSessionText,
            prefs.voicePunct, prefs.voicePangu, prefs.voiceFillers)
        ic.beginBatchEdit()
        if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
        ic.commitText(edit.insert, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Insert(edit.insert))
        lastVoiceInsert = edit.insert
        voiceSessionText = true
        voiceView.canUndo = true
        voiceView.partial = ""
    }

    /** 撤回上一句: takes the last sentence back out of the field. */
    private fun undoVoice() {
        val ic = currentInputConnection ?: return
        val t = lastVoiceInsert
        if (t.isEmpty()) return
        if (textBefore(t.length) == t) ic.deleteSurroundingText(t.length, 0)
        lastVoiceInsert = ""
        voiceView.canUndo = false
        haptics.play(HapticEvent.GESTURE)
    }

    // ---- 表情查找 ------------------------------------------------------------------------

    /**
     * Opens the letters with the bar showing matching emoji. The query starts from what is
     * selected, or the last word before the cursor, so one tap already finds related emoji.
     */
    private fun startEmojiSearch() {
        commitComposingFirst()
        emojiSearch = true
        searchQuery.setLength(0)
        searchQuery.append(contextWord())
        haptics.play(HapticEvent.FUNCTION)
        showPanel(Panel.LETTERS)
    }

    private fun stopEmojiSearch(toPanel: Panel? = Panel.EMOJI) {
        if (!emojiSearch) return
        emojiSearch = false
        if (composing) RimeBridge.clearComposition()
        searchQuery.setLength(0)
        if (toPanel != null) showPanel(toPanel)
    }

    private fun contextWord(): String {
        val ic = currentInputConnection ?: return ""
        ic.getSelectedText(0)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(12) }
        val before = ic.getTextBeforeCursor(24, 0)?.toString().orEmpty().trimEnd()
        if (before.isEmpty()) return ""
        return if (isCjk(before.last())) before.takeLastWhile { isCjk(it) }.takeLast(6)
        else before.takeLastWhile { it.isLetter() }.takeLast(20)
    }

    private fun searchType(text: String) {
        searchQuery.append(text)
        syncBar()
    }

    override fun onExpand() {
        showPanel(if (panel == Panel.CANDIDATES) Panel.LETTERS else Panel.CANDIDATES)
    }

    override fun onClipChip(text: String) {
        commit(text, pairable = false)
        history.push(EditHistory.Op.Insert(text))
        pendingClip = null
        syncBar()
    }

    override fun onDismissSuggestions() {
        if (emojiSearch) { stopEmojiSearch(); return }
        if (translateMode) { stopTranslate(); return }
        predictions = emptyList()
        pendingClip = null
        syncBar()
    }

    override fun onMathTab(id: String) {
        val m = math ?: return
        val from = m.tabs.indexOfFirst { it.id == mathTab }
        val to = m.tabs.indexOfFirst { it.id == id }
        mathTab = id
        refreshMath(slide = (to - from).coerceIn(-1, 1))
    }

    private fun buildMathPanel() {
        mathPanel = com.kk.shurufa.keyboard.MathPanel(this, prefs) { newKeyboard() }
        mathPanel.tabs.theme = theme
        mathPanel.tabs.onTab = { id -> onMathTab(id) }
        mathPanel.tabs.onFeedback = { haptics.play(HapticEvent.FUNCTION) }
    }

    private fun refreshMath(slide: Int) {
        val m = math ?: return
        if (m.tabs.none { it.id == mathTab }) mathTab = m.tabs.first().id
        mathPanel.show(m, mathTab, slide)
    }

    override fun onMathFormat(format: String) { prefs.put(Prefs.MATH_FORMAT, format) }
    override fun onResultChip(text: String) { commit(text, pairable = false); bar.showResult(null) }

    private fun openSettings(page: String? = null) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (page != null) putExtra("page", page) })
    }
}

/** The keyboard's own undo stack: what it typed, deleted or cleared. */
class EditHistory {
    sealed class Op {
        data class Insert(val text: String) : Op()
        data class Delete(val text: String) : Op()
        data class Clear(val text: String, val cursor: Int) : Op()
    }

    private val ops = ArrayDeque<Op>()
    private var lastAt = 0L

    fun push(op: Op) {
        val now = SystemClock.uptimeMillis()
        val prev = ops.lastOrNull()
        // consecutive single deletions or insertions within a short pause undo together
        if (now - lastAt < 1500 && prev != null) {
            if (op is Op.Delete && prev is Op.Delete) { ops.removeLast(); ops.addLast(Op.Delete(op.text + prev.text)); lastAt = now; return }
            if (op is Op.Insert && prev is Op.Insert && op.text.length <= 2 && !op.text.contains('\n')) {
                ops.removeLast(); ops.addLast(Op.Insert(prev.text + op.text)); lastAt = now; return
            }
        }
        ops.addLast(op)
        while (ops.size > 50) ops.removeFirst()
        lastAt = now
    }

    fun pop(): Op? = ops.removeLastOrNull().also { lastAt = 0 }
    fun clear() = ops.clear()
}

/** Recent clipboard texts, newest first; pinned entries stay when the rest is cleared. */
class ClipHistory(private val file: File, private val keep: () -> Int) {
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
        while (list.count { !it.pinned } > keep()) list.remove(list.last { !it.pinned })
        save()
    }

    fun togglePin(text: String) {
        val i = list.indexOfFirst { it.text == text }
        if (i >= 0) { list[i] = list[i].copy(pinned = !list[i].pinned); save() }
    }

    fun remove(text: String) { list.removeAll { it.text == text }; save() }
    fun moveToFront(text: String) {
        val i = list.indexOfFirst { it.text == text }
        if (i > 0) { list.add(0, list.removeAt(i)); save() }
    }
    fun clearUnpinned() { list.removeAll { !it.pinned }; save() }
    /** Everything, newest first; favorites stay where they were copied. */
    fun entries(): List<Entry> = list.toList()
    fun favorites(): List<Entry> = list.filter { it.pinned }

    companion object {
        /** A short tag for what a clip looks like. */
        fun kind(t: String): String {
            val s = t.trim()
            return when {
                Regex("^(https?://|www\\.)\\S+$").matches(s) -> "链接"
                Regex("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$").matches(s) -> "邮箱"
                Regex("^\\d{4,8}$").matches(s) -> "验证码"
                Regex("^\\+?[\\d\\s()-]{7,18}$").matches(s) && s.count { it.isDigit() } >= 7 -> "号码"
                else -> ""
            }
        }
    }
}
