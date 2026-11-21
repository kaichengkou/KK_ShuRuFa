package com.kk.shurufa.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.keyboard.EditPanel
import com.kk.shurufa.keyboard.FamilyRows
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyboardLayout
import com.kk.shurufa.keyboard.KeyboardListener
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.MathLayout
import com.kk.shurufa.keyboard.MathPanel
import com.kk.shurufa.keyboard.Motion
import com.kk.shurufa.keyboard.NumPadView
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PopupOverlay
import com.kk.shurufa.keyboard.QuickPanel
import com.kk.shurufa.keyboard.SheetHandle
import com.kk.shurufa.keyboard.SymbolCategory
import com.kk.shurufa.online.TranslateService
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import com.kk.shurufa.settings.KeyOverrides
import com.kk.shurufa.settings.SettingsActivity
import kotlin.math.abs

/**
 * The input method. It owns the window, decides which panel fills the area under the bar and
 * what the bar shows, routes keys (Chinese through Rime, the rest straight to the field), and
 * hosts the tools — emoji, clipboard, translation, AI, voice — each kept in a class of its own.
 * Everything written into the field goes through [Editor].
 */
class KkImeService : InputMethodService(), Host, KeyboardListener, CandidateBar.Listener {

    override val context: Context get() = this
    override val prefs by lazy { Prefs(this) }
    override val editor by lazy { Editor(this, prefs) }
    override lateinit var haptics: Haptics
        private set
    override lateinit var kbTheme: KbTheme
        private set
    override lateinit var overlay: PopupOverlay
        private set
    override var panel = Panel.LETTERS
        private set
    override val query = StringBuilder()
    override var sink: Sink? = null
        private set

    private lateinit var repo: LayoutRepo
    private lateinit var predictor: Predictor
    private lateinit var zh: ZhComposer
    private val translateService by lazy { TranslateService(prefs) }
    private lateinit var emoji: EmojiFeature
    private lateinit var clipboard: ClipboardFeature
    private lateinit var translate: TranslateFeature
    private lateinit var ai: AiFeature
    private lateinit var voice: VoiceFeature
    private val handler = Handler(Looper.getMainLooper())

    // views of the current input view
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var bar: CandidateBar
    private lateinit var content: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var candPanel: LinearLayout
    private lateinit var candGrid: GridPanel
    private lateinit var candHandle: SheetHandle
    private lateinit var symbolPanel: LinearLayout
    private lateinit var symRail: GridPanel
    private lateinit var symCells: GridPanel
    private lateinit var symBottom: KeyboardView
    private lateinit var editPanel: EditPanel
    private lateinit var mathPanel: MathPanel
    private lateinit var numPad: NumPadView
    private lateinit var quickPanel: QuickPanel
    private lateinit var clipPanel: View
    private lateinit var emojiPanel: View
    private lateinit var aiPanel: View
    private lateinit var voicePanel: View
    private val built get() = ::root.isInitialized

    private var navInset = 0
    /** Height told to the app (from the bottom); -1 means the column's current height. */
    private var reportH = -1
    private var heightAnim: ValueAnimator? = null
    /** 临时拉高: the clipboard and the candidate grid can take more of the screen. */
    private var tall = false

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
    private val recentSymbols = mutableListOf<String>()
    private var lastSymbolsAt = 0L

    /** 拼音编辑: the bar shows the typed letters large, with a caret that can be placed precisely. */
    private var pinyinEdit = false
    private var predictions: List<String> = emptyList()
    private var predictionLabel = ""
    private var searchResults: List<String> = emptyList()
    /** A notice raised before the bar existed (a layout file that failed to load). */
    private var pendingNotice: String? = null

    private val rimeListener: (RimeEngine.State) -> Unit = { onRimeState(it) }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        haptics = Haptics(this, prefs)
        repo = LayoutRepo(this, overrides = { KeyOverrides.load(prefs, it) })
        predictor = Predictor(this)
        zh = ZhComposer(this, prefs).also { it.preload() }
        if (prefs.zhPredict) predictor.load()
        if (prefs.packPoetry) predictor.loadPoetry()
        recentSymbols += prefs.state.getString(Prefs.RECENT_SYMBOLS, "")!!.split('\u0001').filter { it.isNotEmpty() }
        emoji = EmojiFeature(this).also { it.load(); it.maybeUpdate() }
        clipboard = ClipboardFeature(this).also { it.register() }
        translate = TranslateFeature(this, translateService)
        ai = AiFeature(this)
        voice = VoiceFeature(this, translateService)
        prefs.sp.registerOnSharedPreferenceChangeListener(settingsListener)
        prefs.state.registerOnSharedPreferenceChangeListener(stateListener)
        RimeEngine.addListener(rimeListener)
        RimeEngine.start(this)
        lang = prefs.languages.firstOrNull() ?: "zh"
    }

    override fun onDestroy() {
        voice.destroy()
        clipboard.unregister()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(settingsListener)
        prefs.state.unregisterOnSharedPreferenceChangeListener(stateListener)
        RimeEngine.removeListener(rimeListener)
        handler.removeCallbacksAndMessages(null)
        haptics.release()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // the recognizer's models are by far the biggest thing held; they load again when needed
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            voice.trimMemory()
            if (sink != Sink.TRANSLATE) translateService.trimMemory()
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onSetting(key) }

    /** Settings that change how the keyboard is built rebuild it; every other one applies live. */
    private fun onSetting(key: String?) {
        if (key == null) return
        if (key.startsWith("hap_")) { haptics.invalidate(); return }
        if (Prefs.rebuildsKeyboard(key)) {
            layouts.clear(); math = null
            if (built) setInputView(onCreateInputView())
            return
        }
        when (key) {
            Prefs.ZH_EMOJI, Prefs.ZH_TRAD -> applyRimeOptions()
            Prefs.ZH_PREDICT -> if (prefs.zhPredict) predictor.load() else { predictions = emptyList(); syncBar() }
            Prefs.ZH_CORRECTION -> RimeEngine.redeploy(this)
            Prefs.PACK_POETRY -> { if (prefs.packPoetry) predictor.loadPoetry() else predictor.dropPoetry(); RimeEngine.redeploy(this) }
            Prefs.MATH_FORMAT -> syncBar()
            Prefs.STRESS_MODE -> if (built && panel == Panel.LETTERS) keyboard.setRows(lettersRows())
            Prefs.TOOLBAR_ITEMS -> if (built) bar.invalidate()
            Prefs.CLIP_FRICTION, Prefs.CLIP_MAX_CHARS, Prefs.CLIP_COLUMNS -> if (built) { clipboard.applyLook(); clipboard.refresh(keep = true) }
        }
        if (built && panel == Panel.QUICK) refreshQuick()
    }

    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // the settings downloaded newer emoji
        if (key == Prefs.EMOJI_RELOAD) emoji.load()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (built) setInputView(onCreateInputView())
    }

    override fun onEvaluateFullscreenMode() = false

    // ---- the input view -----------------------------------------------------------------

    override fun onCreateInputView(): View {
        // a rebuilt view starts without a query: the strip it typed into is replaced
        if (sink != null) dropSink()
        ghost = null
        kbTheme = KbTheme.resolve(this, prefs)
        loadData()
        // The input window keeps one tall, mostly transparent size. Panels then change height inside
        // it, so no animation or drag ever resizes the window or makes the app below lay out again;
        // onComputeInsets tells the app (and touch dispatch) where the keyboard actually is.
        root = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(windowH(), MeasureSpec.EXACTLY))
            }
        }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(kbTheme.background) }
        overlay = PopupOverlay(this, prefs).also {
            it.theme = kbTheme; it.onTick = { haptics.play(HapticEvent.OPTION) }
            it.onStickyChanged = { root.requestLayout() }
        }
        bar = CandidateBar(this, prefs).also { it.theme = kbTheme; it.listener = this; it.overlay = overlay }
        // 按住工具栏拖动: with a resizable panel open, the toolbar works as its grab bar too
        bar.onVerticalDrag = { phase, rawY, v ->
            val h = if (panel == Panel.CLIPBOARD) clipboard.handle else null
            if (h != null) when (phase) { 0 -> h.begin(rawY); 1 -> h.drag(rawY); else -> h.end(v) }
        }
        content = FrameLayout(this)
        keyboard = newKeyboard()
        buildCandidatePanel()
        buildSymbolPanel()
        buildEditPanel()
        buildMathPanel()
        buildNumPad()
        voicePanel = voice.build()
        aiPanel = ai.build()
        clipPanel = clipboard.build { newHandle() }
        emojiPanel = emoji.build()
        quickPanel = QuickPanel(this, prefs).also { it.theme = kbTheme; it.onTile = { id -> onQuickTile(id) }; it.onPress = { haptics.play(HapticEvent.LETTER) } }
        column.addView(translate.build(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
            navInset = bottom
            column.setPadding(0, 0, 0, bottom)
            ins
        }
        paintNavigationBar()
        show(panel)
        // a notice raised while building waits for the keyboard to be laid out
        root.post { pendingNotice?.let { pendingNotice = null; notice(it) } }
        return root
    }

    /** Tall enough for the tallest panel plus room above it for key previews and long-press menus. */
    private fun windowH(): Int {
        val tallest = maxOf(tallH(), mathH() + dp(8f).toInt()) + barH
        return minOf((resources.displayMetrics.heightPixels * 0.86f).toInt(), tallest + navInset + dp(96f).toInt())
    }

    private val insetLoc = IntArray(2)

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!built || root.height == 0 || !root.isShown) return
        root.getLocationInWindow(insetLoc)
        val bottom = insetLoc[1] + root.height
        val shown = column.height
        // while a panel is dragged taller it covers the app; once it rests, the app makes room once,
        // so what is pasted is not hidden behind it
        var reported = if (reportH > 0) reportH else shown
        val sheet = if (panel == Panel.CLIPBOARD) clipboard.handle else if (panel == Panel.CANDIDATES) candHandle else null
        if (sheet != null && sheet.moving) reported = minOf(reported, baseH + barH + navInset)
        val top = bottom - reported
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        // an open menu takes taps anywhere (a tap outside it closes it)
        if (overlay.sticky) outInsets.touchableRegion.set(0, insetLoc[1], root.width, bottom)
        else outInsets.touchableRegion.set(0, minOf(top, bottom - shown), root.width, bottom)
    }

    /** The system draws its IME buttons below the keys; give that strip the keyboard's color. */
    private fun paintNavigationBar() {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        w.navigationBarColor = kbTheme.background
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.setSystemBarsAppearance(
                if (kbTheme.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = if (kbTheme.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    override fun newKeyboard() = KeyboardView(this, prefs).also { it.theme = kbTheme; it.listener = this; it.overlay = overlay }

    private fun loadData() {
        try {
            math = repo.math()
            symbols = repo.symbols()
        } catch (e: Exception) {
            pendingNotice = "布局文件有错误：${e.message}"
        }
    }

    private fun layout(id: String): KeyboardLayout = layouts.getOrPut(id) {
        try { repo.keyboard(id) } catch (e: Exception) {
            notice("布局 $id 读取失败：${e.message}")
            KeyboardLayout(id, id, "direct", emptyList())
        }
    }

    private val curLang get() = tempLang ?: lang
    override val language: String get() = curLang
    override val isZh: Boolean get() = layout(curLang).engine == "rime"
    private val composing get() = isZh && RimeEngine.ready && RimeBridge.input().isNotEmpty()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        tempLang = null
        predictions = emptyList()
        editor.reset()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (prefs.perAppLanguage && !restarting) prefs.appLanguage(info.packageName ?: "")?.takeIf { it in prefs.languages }?.let { lang = it }
        val isPassword = cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORDS
        val isAddress = cls == InputType.TYPE_CLASS_TEXT && variation in ADDRESSES
        if ((isPassword || isAddress) && isZh) tempLang = prefs.languages.firstOrNull { layout(it).engine != "rime" } ?: "en"
        panel = when {
            cls == InputType.TYPE_CLASS_PHONE -> Panel.PHONE
            (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) && prefs.numberFieldMath -> { mathTab = "basic"; numPanel() }
            else -> Panel.LETTERS
        }
        shift = 0; autoShift = false
        if (built) { paintNavigationBar(); show(panel) }
        updateAutoCap()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (sink != null) dropSink()
        if (panel == Panel.VOICE) voice.onClosed()
        if (RimeEngine.ready) RimeBridge.clearComposition()
        zh.reset()
        predictions = emptyList()
        editor.finishComposingText()
        if (built) syncBar()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        editor.onSelection(newSelStart, newSelEnd)
        if (built && panel == Panel.CURSOR) syncEditPanel()
    }

    private fun onRimeState(s: RimeEngine.State) {
        if (s == RimeEngine.State.READY) applyRimeOptions()
        syncBar()
    }

    private fun applyRimeOptions() {
        if (!RimeEngine.ready) return
        RimeBridge.setOption("emoji", prefs.zhEmoji)
        RimeBridge.setOption("traditionalization", prefs.zhTraditional)
    }

    // ---- panels -------------------------------------------------------------------------

    private var lettersKey = ""
    private var lettersCache: List<List<Key>> = emptyList()

    /** The letter rows with the switches that change them applied; the same list while nothing changed. */
    private fun lettersRows(): List<List<Key>> {
        val key = "$curLang|${prefs.stressMode}|${prefs.topRowScripts}|${prefs.numberRow}|${System.identityHashCode(layout(curLang))}"
        if (key != lettersKey) { lettersCache = buildLettersRows(); lettersKey = key }
        return lettersCache
    }

    private fun buildLettersRows(): List<List<Key>> {
        var base = layout(curLang).rows
        if (curLang == "ru" && prefs.stressMode) {
            // 重音模式: every Russian vowel flicks up to its stressed form
            base = base.map { row ->
                row.map { k ->
                    val ch = k.main?.text
                    if (ch != null && ch.length == 1 && ch[0] in VOWELS) k.copy(flicks = k.flicks + (Dir.UP to Output.of(ch + "́")))
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

    private val barH get() = dp(CandidateBar.HEIGHT_DP).toInt()
    private val baseH get() = kbHeight(lettersRows().size.coerceAtLeast(4))
    /** 学术 is taller than the letters: a tab row, four rows of keys and the bottom row. */
    private fun mathH(): Int {
        val kh = dp(prefs.keyHeightDp.toFloat())
        val g = dp(prefs.keyGapDp)
        return (dp(40f) + dp(2f) + 4 * kh * MathPanel.ROW + 3 * g + g + kh + dp(prefs.bottomPaddingDp + 2f)).toInt()
    }
    private fun tallH() = maxOf(baseH + dp(40f).toInt(), (resources.displayMetrics.heightPixels * 0.56f).toInt() - barH)
    private fun inFamily(p: Panel) = p == Panel.NUM || p == Panel.MATH || p == Panel.SYMBOLS

    private fun toolFor(p: Panel) = when (p) {
        Panel.CURSOR -> "cursor"
        Panel.CLIPBOARD -> "clipboard"
        Panel.QUICK -> "quick"
        Panel.EMOJI -> "emoji"
        Panel.VOICE -> "mic"
        Panel.AI -> "ai"
        else -> null
    }

    override fun show(panel: Panel) = showPanel(panel)

    private fun showPanel(p: Panel) {
        if (!built) { panel = p; return }
        if (overlay.sticky) overlay.dismissSticky()
        val from = panel
        if (p != Panel.LETTERS && sink != null) dropSink()
        if (from == Panel.VOICE && p != Panel.VOICE) voice.onClosed()
        if (from == Panel.AI && p != Panel.AI) ai.cancel()
        panel = p
        if (p != Panel.LETTERS && p != Panel.CANDIDATES) pinyinEdit = false
        val oldTotal = (if (bar.visibility == View.VISIBLE) bar.height else 0) + content.height
        // the panel that leaves fades out where it is while the new one fades in over it
        val leaving = content.getChildAt(0)
        clearGhost()
        if (from != p && leaving != null) fadeOut(leaving)
        content.removeAllViews()
        heightAnim?.cancel()
        reportH = -1
        val base = baseH
        val tallH = tallH()
        val canTall = p == Panel.CLIPBOARD || p == Panel.CANDIDATES
        // 记住符号: back to the symbols only if they were the last panel of the family used
        if (from == Panel.SYMBOLS && p != Panel.SYMBOLS) lastSymbolsAt = if (inFamily(p)) 0L else SystemClock.uptimeMillis()
        for (h in listOf(clipboard.handle, candHandle)) { h.low = base; h.high = tallH; h.current = if (tall) tallH else base }
        // the symbol panel hides the toolbar and uses its height itself
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
        val entering = from != p
        when (p) {
            Panel.LETTERS -> { keyboard.setRows(lettersRows()); content.addView(keyboard, fixed) }
            Panel.PHONE -> { keyboard.setRows(layout("phone").rows); content.addView(keyboard, fixed) }
            Panel.MATH -> {
                if (math == null) { showPanel(Panel.LETTERS); return }
                refreshMath(slide = 0)
                content.addView(mathPanel, fixed)
            }
            Panel.NUM -> { numPad.keys.setRows(layout("num").rows); numPad.setItems(NUM_OPS); content.addView(numPad, fixed) }
            Panel.CURSOR -> { syncEditPanel(); content.addView(editPanel, fixed) }
            Panel.SYMBOLS -> { refreshSymbols(); content.addView(symbolPanel, fixed) }
            Panel.CLIPBOARD -> { clipboard.refresh(); content.addView(clipPanel, fixed) }
            Panel.QUICK -> { refreshQuick(); content.addView(quickPanel, fixed); if (entering) quickPanel.animateIn() }
            Panel.EMOJI -> { emoji.refresh(); content.addView(emojiPanel, fixed) }
            Panel.AI -> { content.addView(aiPanel, fixed); if (entering) ai.onOpened() }
            Panel.VOICE -> { content.addView(voicePanel, fixed); if (entering) voice.onOpened() }
            Panel.CANDIDATES -> {
                val cands = RimeBridge.candidates(300).toList()
                candGrid.notes = zh.annotations(cands, RimeBridge.candidateComments(cands.size).toList())
                candGrid.setItems(cands)
                content.addView(candPanel, fixed)
            }
        }
        if (entering && p != Panel.LETTERS && p != Panel.PHONE && p != Panel.QUICK) animateIn(content.getChildAt(0))
        bar.expanded = p == Panel.CANDIDATES
        bar.activeTool = when (sink) { Sink.EMOJI -> "emoji"; Sink.TRANSLATE -> "translate"; Sink.AI -> "ai"; null -> toolFor(p) }
        syncBar()
        animateHeight(oldTotal, if (noBar) 0 else barH)
    }

    /** Eases the keyboard's height from what it was to what the new panel needs. */
    private fun animateHeight(oldTotal: Int, newBar: Int) {
        if (!Motion.on(prefs) || oldTotal <= 0) return
        content.measure(View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val target = content.measuredHeight
        val start = oldTotal - newBar
        if (start <= 0 || abs(start - target) < dp(2f)) return
        // while it moves the app keeps the larger of the two sizes, so it lays out at most once
        reportH = maxOf(start, target) + newBar + navInset
        heightAnim = ValueAnimator.ofInt(start, target).apply {
            duration = Motion.dur(prefs, Motion.PANEL)
            interpolator = Motion.ENTER
            addUpdateListener {
                val h = it.animatedValue as Int
                content.layoutParams = content.layoutParams.apply { height = h }; content.requestLayout()
                // the leaving panel stays on the keyboard's bottom edge while the top edge moves
                ghost?.translationY = (h - ghostBase).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    reportH = -1
                    content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }; content.requestLayout()
                }
            })
            start()
        }
    }

    /** The panel fading out, drawn on the content's overlay: it is seen, but takes no touches. */
    private var ghost: View? = null
    /** The content's height when [ghost] left it. */
    private var ghostBase = 0

    private fun fadeOut(v: View) {
        if (!Motion.on(prefs) || !v.isLaidOut || v.alpha < 0.5f) return
        // whatever it was still doing (rising in) stops; from here the height animation moves it
        v.animate().cancel()
        v.translationY = 0f
        ghostBase = content.height
        content.overlay.add(v)
        ghost = v
        v.animate().alpha(0f).setDuration(Motion.dur(prefs, 150)).setInterpolator(Motion.EXIT).withLayer()
            .withEndAction { if (ghost === v) clearGhost() }.start()
    }

    private fun clearGhost() {
        val g = ghost ?: return
        ghost = null
        g.animate().cancel()
        content.overlay.remove(g)
        g.alpha = 1f; g.translationY = 0f
    }

    /** A panel arrives: it rises a little into place as it fades in. */
    private fun animateIn(v: View?) {
        if (v == null || !Motion.on(prefs)) return
        v.alpha = 0f; v.translationY = dp(14f)
        v.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(prefs, Motion.PANEL))
            .setInterpolator(Motion.ENTER).withLayer().start()
    }

    // ---- the bar ------------------------------------------------------------------------

    override fun syncBar() {
        if (!built) return
        val s = sink
        val letters = panel == Panel.LETTERS
        when {
            panel == Panel.MATH -> bar.showMath(prefs.mathFormat, simple = false)
            panel == Panel.NUM -> bar.showMath(prefs.mathFormat, simple = true)
            // a query the user opened comes first: it works without the Chinese engine
            s == Sink.TRANSLATE && !composing && letters -> { bar.showToolbar(); translate.sync() }
            s == Sink.AI && !composing && letters -> {
                val q = query.toString()
                bar.showPredictions(if (q.isBlank()) emptyList() else listOf("▶ 执行"),
                    if (q.isBlank()) "AI 指令：输入你想怎么改（如“改成适合发朋友圈的语气”）" else "AI 指令：$q")
            }
            s == Sink.EMOJI && !composing && letters -> {
                val q = query.toString().trim()
                searchResults = emoji.search(q)
                bar.showPredictions(searchResults, when {
                    q.isEmpty() -> "查找表情：输入中文或英文关键词"
                    searchResults.isEmpty() -> "没有找到和「$q」相关的表情"
                    else -> "查找「$q」· 点按发送"
                })
            }
            isZh && RimeEngine.state == RimeEngine.State.DEPLOYING && letters -> bar.showMessage("正在部署雾凇拼音词库，首次约需一分钟…")
            isZh && RimeEngine.state == RimeEngine.State.PREPARING && letters -> bar.showMessage("正在准备中文词库…")
            isZh && RimeEngine.state == RimeEngine.State.FAILED && letters -> bar.showMessage("中文引擎没有启动 · 点按重试", tappable = true)
            composing && pinyinEdit -> bar.showEdit(RimeBridge.input(), RimeBridge.caretPos(), zh.gaps(RimeBridge.input(), RimeBridge.preedit()))
            composing -> {
                val pre = RimeBridge.preedit()
                zh.track(pre)
                val cands = RimeBridge.candidates(CANDIDATES_ON_BAR).toList()
                bar.showCandidates(pre, cands, zh.annotations(cands, RimeBridge.candidateComments(cands.size).toList()), zh.caretInPreedit(pre, RimeBridge.cursor()))
                if (prefs.zhPreeditInline) editor.compose(RimeBridge.input())
            }
            predictions.isNotEmpty() && letters -> bar.showPredictions(predictions, predictionLabel)
            clipboard.pendingChip() != null && letters -> clipboard.pendingChip()?.let { bar.showClip(it, ClipHistory.kind(it)) }
            else -> {
                bar.showToolbar()
                if (panel == Panel.CANDIDATES) showPanel(Panel.LETTERS)
            }
        }
    }

    /** A short notice floating above the keyboard (instead of a toast over the app). */
    override fun notice(text: String) {
        if (built && column.height > 0) overlay.toast(text, column.top.toFloat()) else pendingNotice = text
    }

    // ---- the candidate grid, symbols, the edit pad, numbers, the quick panel --------------

    private fun buildCandidatePanel() {
        candGrid = GridPanel(this, prefs).apply {
            theme = kbTheme; cellStyle = GridPanel.CellStyle.FLAT; columns = 8; rowHeightDp = 50f; textSp = 19f
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
        candPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candPanel.addView(candHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        candPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** 面板高度档位: dragging the grab bar resizes the open panel live; it settles on one of two heights. */
    private fun newHandle() = SheetHandle(this, prefs).apply {
        theme = kbTheme
        onFeedback = { k -> haptics.play(if (k == "gesture") HapticEvent.GESTURE else HapticEvent.OPTION) }
        onHeight = { h -> content.getChildAt(0)?.let { v -> v.layoutParams = v.layoutParams.apply { height = h }; v.requestLayout() } }
        onSettled = { t -> tall = t }
        onRest = { root.requestLayout() }
    }

    private fun buildSymbolPanel() {
        symbolPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        symRail = GridPanel(this, prefs).apply {
            theme = kbTheme; columns = 1; rowHeightDp = 38f; textSp = 14f; cellStyle = GridPanel.CellStyle.RAIL
            onItem = { i -> symCat = symCatIds().getOrElse(i) { "recent" }; refreshSymbols() }
            onFeedback = { haptics.play(HapticEvent.FUNCTION) }
        }
        symCells = GridPanel(this, prefs).apply {
            theme = kbTheme; cellStyle = GridPanel.CellStyle.SOFT; rowHeightDp = 42f
            emptyText = "用过的符号会出现在这里"
            onItem = { i -> symItems.getOrNull(i)?.let { onSymbol(it) } }
            onFeedback = { haptics.play(HapticEvent.LETTER) }
        }
        top.addView(symRail, LinearLayout.LayoutParams(dp(62f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(symCells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        symBottom = newKeyboard()
        symBottom.setRows(listOf(FamilyRows.symbols()))
        symbolPanel.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        symbolPanel.addView(symBottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun symCatIds() = listOf("recent") + symbols.map { it.id }

    private fun refreshSymbols() {
        if (symbols.isEmpty()) return
        // 最近 holds what was typed; with nothing typed yet the panel opens on the first category
        if (symCat.isEmpty()) symCat = if (recentSymbols.isNotEmpty()) "recent" else symbols.first().id
        val idx = symCatIds().indexOf(symCat).coerceAtLeast(0)
        val cat = symbols.getOrNull(idx - 1)
        symItems = if (idx == 0) recentSymbols.map { Output.of(it) } else cat?.items.orEmpty()
        val wide = cat?.wide == true
        symRail.selectedIndex = idx
        symRail.setItems(listOf("最近") + symbols.map { it.name }, resetScroll = false)
        symCells.columns = if (wide) 3 else 6
        symCells.textSp = if (wide) 14f else 18.5f
        symCells.marks = emptySet()
        symCells.setItems(symItems.map { it.label })
        symBottom.lockOn = symLock
    }

    private fun onSymbol(o: Output) {
        finishComposing()
        lastSymbolsAt = SystemClock.uptimeMillis()
        editor.type(o.text)
        recentSymbols.remove(o.text)
        recentSymbols.add(0, o.text)
        while (recentSymbols.size > 30) recentSymbols.removeAt(recentSymbols.size - 1)
        prefs.state.edit().putString(Prefs.RECENT_SYMBOLS, recentSymbols.joinToString("\u0001")).apply()
        if (!symLock && prefs.symbolAutoReturn) showPanel(Panel.LETTERS)
    }

    private fun buildEditPanel() {
        editPanel = EditPanel(this, prefs).apply {
            theme = kbTheme
            labels = prefs.cursorLabels
            onFeedback = { k -> haptics.play(when (k) { "repeat" -> HapticEvent.REPEAT; "touch" -> HapticEvent.FUNCTION; else -> HapticEvent.CURSOR }) }
            onAction = { id -> onEditAction(id) }
        }
    }

    private fun syncEditPanel() {
        editPanel.selecting = editor.selectMode
        editPanel.hasSelection = editor.hasSelection
    }

    private fun onEditAction(id: String) {
        when (id) {
            "undo" -> undo()
            "select" -> {
                editor.selectMode = !editor.selectMode
                // leaving selection mode drops the selection back to a caret at its end
                if (!editor.selectMode && editor.hasSelection) editor.collapseSelection()
            }
            "up" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_RIGHT)
            "home" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_HOME, editor.shiftMeta())
            "end" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_END, editor.shiftMeta())
            "select_all" -> { editor.contextMenu(android.R.id.selectAll); editor.selectMode = true }
            "backspace" -> { haptics.play(HapticEvent.REPEAT); editor.deleteBackward() }
            "copy" -> { editor.contextMenu(android.R.id.copy); editor.selectMode = false; editor.collapseSelection() }
            "cut" -> { editor.contextMenu(android.R.id.cut); editor.selectMode = false }
            "paste" -> { editor.contextMenu(android.R.id.paste); editor.selectMode = false }
        }
        syncEditPanel()
    }

    /** 简易/科学: !#1 and number fields open the number keyboard in the mode used last. */
    private fun numPanel() = if (prefs.numMode == "sci") Panel.MATH else Panel.NUM

    private fun showCalcResult() {
        if (!prefs.mathCalc) return
        val line = editor.textBefore(200).substringAfterLast('\n').dropLast(1)
        Calc.eval(line.substringAfterLast('='))?.let { bar.showResult(Calc.format(it)) }
    }

    private fun buildNumPad() {
        val strip = GridPanel(this, prefs).apply {
            theme = kbTheme; cellStyle = GridPanel.CellStyle.SOFT; columns = 1; textSp = 20f
            cellPadDp = 0f; cellGapDp = prefs.keyGapDp; scrollHint = true
            onItem = { i -> NUM_OPS.getOrNull(i)?.let { onNumOp(it) } }
            onFeedback = { haptics.play(HapticEvent.LETTER) }
        }
        numPad = NumPadView(this, newKeyboard(), strip)
    }

    private fun onNumOp(op: String) {
        finishComposing()
        editor.type(op, pairable = op == "(")
        if (op == "=") showCalcResult() else bar.showResult(null)
    }

    private fun buildMathPanel() {
        mathPanel = MathPanel(this, prefs) { newKeyboard() }
        mathPanel.theme = kbTheme
        mathPanel.tabs.onTab = { id -> onMathTab(id) }
        mathPanel.tabs.onFeedback = { haptics.play(HapticEvent.FUNCTION) }
    }

    private fun onMathTab(id: String) {
        val m = math ?: return
        val from = m.tabs.indexOfFirst { it.id == mathTab }
        val to = m.tabs.indexOfFirst { it.id == id }
        mathTab = id
        refreshMath(slide = (to - from).coerceIn(-1, 1))
    }

    private fun refreshMath(slide: Int) {
        val m = math ?: return
        if (m.tabs.none { it.id == mathTab }) mathTab = m.tabs.first().id
        mathPanel.show(m, mathTab, slide)
    }

    private fun mathOut(o: Output) {
        val text = if (prefs.mathFormat == "tex") o.tex else o.text
        editor.type(text, pairable = false)
        bar.showResult(null)
        if (o.label == "=" && prefs.mathFormat == "uni") showCalcResult()
    }

    /**
     * 快捷面板, in order: typing switches, look and feedback, the tools not already on the toolbar,
     * then the keyboard itself. Panels reachable from !#1 (数学、符号) are not repeated here.
     */
    private fun refreshQuick() {
        val themeName = when (prefs.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "自动" }
        val onBar = prefs.toolbar.toSet()
        val tools = listOf("ai" to "AI 改写", "mic" to "语音", "translate" to "翻译", "cursor" to "文字编辑", "clipboard" to "剪贴板", "emoji" to "表情")
            .filter { it.first !in onBar }
            .map { (id, name) -> QuickPanel.Tile("tool:$id", name) }
        quickPanel.tiles = listOf(
            QuickPanel.Tile("predict", "联想", on = prefs.zhPredict),
            QuickPanel.Tile("correct", "纠错", on = prefs.zhCorrection),
            QuickPanel.Tile("trad", "繁体", on = prefs.zhTraditional),
            QuickPanel.Tile("emoji", "候选表情", on = prefs.zhEmoji),
            QuickPanel.Tile("pinyin", "拼音模式", on = prefs.pinyinMode),
            QuickPanel.Tile("stress", "俄语重音", on = prefs.stressMode),
            QuickPanel.Tile("vibrate", "振动", on = prefs.vibrate),
            QuickPanel.Tile("sound", "按键音", on = prefs.sound),
            QuickPanel.Tile("theme", "主题", themeName),
        ) + tools + listOf(
            QuickPanel.Tile("toolbar", "工具栏"),
            QuickPanel.Tile("picker", "切换输入法"),
            QuickPanel.Tile("settings", "设置"),
        )
    }

    private fun onQuickTile(id: String) {
        when (id) {
            "trad" -> prefs.put(Prefs.ZH_TRAD, !prefs.zhTraditional)
            "emoji" -> prefs.put(Prefs.ZH_EMOJI, !prefs.zhEmoji)
            "predict" -> prefs.put(Prefs.ZH_PREDICT, !prefs.zhPredict)
            "correct" -> { prefs.put(Prefs.ZH_CORRECTION, !prefs.zhCorrection); notice("正在重新部署词库…") }
            "pinyin" -> prefs.put(Prefs.PINYIN_MODE, !prefs.pinyinMode)
            "stress" -> prefs.put(Prefs.STRESS_MODE, !prefs.stressMode)
            "theme" -> prefs.put(Prefs.THEME, when (prefs.theme) { "system" -> "light"; "light" -> "dark"; else -> "system" })
            "vibrate" -> prefs.put(Prefs.VIBRATE, !prefs.vibrate)
            "sound" -> prefs.put(Prefs.SOUND, !prefs.sound)
            "picker" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            "settings" -> openSettings()
            "toolbar" -> openSettings("toolbar")
            else -> if (id.startsWith("tool:")) { onTool(id.removePrefix("tool:")); return }
        }
        if (panel == Panel.QUICK) refreshQuick()
    }

    // ---- Chinese ------------------------------------------------------------------------

    override fun finishComposing() {
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

    /** Takes what Rime committed (if anything) into the field, or into the query while a sink is open. */
    private fun flushRime() {
        val commit = RimeBridge.commitText()
        if (commit.isNotEmpty() && sink != null) {
            zh.reset()
            query.append(commit)
        } else if (commit.isNotEmpty()) {
            val syl = zh.syllables()
            var out = if (prefs.pinyinMode && commit.any { isCjk(it) }) zh.tones.convert(commit, syl) else commit
            if (prefs.pinyinMode && out != commit) {
                // 拼音模式 separates consecutive words with a space
                val prev = editor.textBefore(1)
                if (prev.isNotEmpty() && prev[0].isLetterOrDigit() && !isCjk(prev[0])) out = " $out"
            }
            zh.reset()
            editor.type(out, pairable = false, syllables = if (out == commit) syl else null)
            predictions = if (prefs.zhPredict && !prefs.pinyinMode && out.any { isCjk(it) })
                predictor.suggest(editor.textBefore(20).ifEmpty { out }) else emptyList()
            predictionLabel = ""
        } else if (prefs.zhPreeditInline && RimeBridge.input().isEmpty()) editor.finishComposingText()
        if (RimeBridge.input().isEmpty()) { zh.reset(); pinyinEdit = false }
        syncBar()
        if (panel == Panel.CANDIDATES) {
            if (composing) {
                val c = RimeBridge.candidates(300).toList()
                candGrid.notes = zh.annotations(c, RimeBridge.candidateComments(c.size).toList())
                candGrid.setItems(c)
            } else showPanel(Panel.LETTERS)
        }
    }

    /**
     * 删除后联想: the deleted characters' syllables (what was typed for them) are looked up again and
     * offered as suggestions; nothing is put back into the input, so typing on simply ignores them.
     * Without a record of the syllables, suggestions follow the text that is left.
     */
    private fun suggestAfterDelete() {
        predictions = emptyList(); predictionLabel = ""
        if (!isZh || panel != Panel.LETTERS) return
        val run = editor.deletedRun
        if (prefs.backspaceReselect && run.isNotEmpty() && RimeEngine.ready) {
            predictions = RimeBridge.query(run.joinToString(""), 24).filter { it.isNotEmpty() }.distinct()
            predictionLabel = "按原拼音 " + run.joinToString(" ")
        } else if (prefs.zhPredict) {
            predictions = predictor.suggest(editor.textBefore(20))
        }
    }

    // ---- the field ----------------------------------------------------------------------

    /** 上滑清空: drops the pinyin while composing, otherwise the whole field (undoable). */
    private fun clearAll() {
        if (composing) { RimeBridge.clearComposition(); flushRime(); return }
        if (!editor.clearAll()) return
        predictions = emptyList()
        syncBar()
        updateAutoCap()
    }

    private fun undo() {
        editor.undo()
        predictions = emptyList()
        syncBar()
    }

    /**
     * 句首大写 for Russian and English. Decided from the text before the cursor, because many
     * apps do not ask for sentence capitalization in their input type.
     */
    private fun updateAutoCap() {
        if (!built || shift == 2) return
        val info = currentInputEditorInfo
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val excluded = variation in PASSWORDS || variation in ADDRESSES
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

    // ---- keys ---------------------------------------------------------------------------

    override fun shiftState() = shift
    override fun spaceLabel(): String = if (panel == Panel.LETTERS) layout(curLang).label else "空格"

    override fun onOutput(key: Key, output: Output) {
        if (panel == Panel.MATH) { mathOut(output); return }
        val t = output.text
        val isMain = output === key.main
        if (sink != null && panel == Panel.LETTERS) {
            // a query: Chinese letters still go through Rime, whose words then join the query
            if (isZh && isMain && t.length == 1 && t[0] in 'a'..'z' && rimeKey(t[0].code)) return
            finishComposing()
            query.append(if (isMain && key.isLetter && shift > 0) t.uppercase() else t)
            syncBar()
            return
        }
        if (panel == Panel.NUM) {
            editor.type(t, pairable = t.length > 1)
            if (t == "=") showCalcResult() else bar.showResult(null)
            return
        }
        predictions = emptyList()
        editor.deletedRun.clear()
        if (isZh && panel == Panel.LETTERS) {
            if (isMain && t.length == 1 && (t[0] in 'a'..'z' || (t == "'" && composing))) {
                if (rimeKey(t[0].code)) return
                if (!RimeEngine.ready) { editor.type(t); return }
            }
            finishComposing()
            editor.type(smartPunct(t))
            syncBar()
            return
        }
        editor.type(if (isMain && key.isLetter && shift > 0) t.uppercase() else t)
        if (isMain && key.isLetter && shift == 1) { shift = 0; autoShift = false; keyboard.invalidate() }
        updateAutoCap()
        if (panel == Panel.LETTERS) syncBar()
    }

    /** 数字后的标点保持半角: after a digit, Chinese punctuation that would break a number is typed half-width. */
    private fun smartPunct(t: String): String {
        if (!prefs.smartPunct) return t
        val before = editor.textBefore(1)
        if (before.isNotEmpty() && before[0].isDigit()) return when (t) { "。" -> "."; "：" -> ":"; "，" -> ","; else -> t }
        return t
    }

    override fun onFunction(key: Key) {
        when (key.fn) {
            "backspace" -> {
                predictions = emptyList()
                when {
                    composing -> rimeKey(RimeBridge.XK_BackSpace)
                    sink != null -> { if (query.isNotEmpty()) query.setLength(query.offsetByCodePoints(query.length, -1)); syncBar() }
                    else -> { editor.deleteBackward(); updateAutoCap(); suggestAfterDelete(); syncBar() }
                }
            }
            "space" -> {
                if (composing) { rimeKey(RimeBridge.XK_space); return }
                if (sink != null) { query.append(' '); syncBar(); return }
                predictions = emptyList()
                val now = SystemClock.uptimeMillis()
                val before = editor.textBefore(2)
                // 双击空格: a full stop after a word
                if (prefs.doubleSpacePeriod && !isZh && panel == Panel.LETTERS && now - lastSpaceAt < 600 &&
                    before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) editor.replaceBefore(1, ". ")
                else editor.type(" ")
                lastSpaceAt = now
                updateAutoCap()
                syncBar()
            }
            "enter" -> when {
                composing -> rimeKey(RimeBridge.XK_Return)
                sink == Sink.AI -> runInstruction()
                sink == Sink.TRANSLATE -> translate.commit()
                sink == Sink.EMOJI -> searchResults.firstOrNull()?.let { emoji.type(it) }
                else -> { predictions = emptyList(); editor.performEnter(); updateAutoCap(); syncBar() }
            }
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
            "voice" -> onTool("mic")
            "symbols" -> {
                finishComposing()
                // back to the symbols if they were used a moment ago, otherwise the number keyboard
                val recent = lastSymbolsAt > 0 && SystemClock.uptimeMillis() - lastSymbolsAt < prefs.symbolMemorySec * 1000L
                showPanel(if (recent) Panel.SYMBOLS else numPanel())
            }
            "sci" -> { prefs.put(Prefs.NUM_MODE, "sci"); showPanel(Panel.MATH) }
            "simple" -> { prefs.put(Prefs.NUM_MODE, "simple"); showPanel(Panel.NUM) }
            "num" -> showPanel(numPanel())
            "more_symbols" -> showPanel(Panel.SYMBOLS)
            "math" -> { finishComposing(); showPanel(Panel.MATH) }
            "abc" -> { editor.selectMode = false; showPanel(Panel.LETTERS) }
            "collapse" -> showPanel(Panel.LETTERS)
            "reinput" -> { RimeBridge.clearComposition(); flushRime(); showPanel(Panel.LETTERS) }
            "undo" -> undo()
            "lock" -> {
                symLock = !symLock
                symBottom.lockOn = symLock
                notice(if (symLock) "已锁定：可以连续输入符号" else "已解锁：点一个符号就回到字母")
            }
            "left" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_DOWN)
            "word_left" -> editor.sendKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or editor.shiftMeta())
            "word_right" -> editor.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON or editor.shiftMeta())
            "home" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_HOME, editor.shiftMeta())
            "end" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_END, editor.shiftMeta())
            "select_all" -> editor.contextMenu(android.R.id.selectAll)
            "copy" -> { editor.contextMenu(android.R.id.copy); editor.selectMode = false }
            "cut" -> { editor.contextMenu(android.R.id.cut); editor.selectMode = false }
            "paste" -> editor.contextMenu(android.R.id.paste)
            "next_slot" -> editor.nextSlot()
        }
    }

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
            val list = DIRS.mapNotNull { key.flicks[it] }
            return if (list.isEmpty()) emptyList() else listOf(list + m)
        }
        val symbols = mutableListOf<Output>()
        val letters = mutableListOf<Output>()
        if (prefs.lpHints) DIRS.forEach { d -> key.flicks[d]?.let { symbols += it } }
        if (prefs.lpDigits && panel == Panel.LETTERS) {
            val col = layout(curLang).rows.firstOrNull()?.indexOf(key) ?: -1
            if (col in 0..9) symbols += Output.of(((col + 1) % 10).toString())
        }
        if (prefs.lpCase && key.isLetter) {
            val up = m.text.uppercase(); val low = m.text.lowercase()
            if (shift > 0) { letters += Output.of(low); letters += Output.of(up) } else { letters += Output.of(up); letters += Output.of(low) }
        }
        if (curLang == "ru" && m.text.length == 1 && m.text[0] in VOWELS) letters += Output.of(m.text + "́")
        for (o in key.long) if (o.text.firstOrNull()?.isLetter() == true) letters += o else symbols += o
        val s = symbols.distinctBy { it.text }
        val l = letters.distinctBy { it.text }.filter { o -> s.none { it.text == o.text } }
        val all = s + l
        if (all.isEmpty() || (all.size == 1 && all[0].text == m.text)) return emptyList()
        return if (s.isEmpty() || l.isEmpty() || all.size <= 5) listOf(all) else listOf(s, l)
    }

    private fun fnOptions(fn: String, key: Key): List<Output> = when (fn) {
        "language", "voice" -> prefs.languages.map { Output(layout(it).label, "§lang:$it") } +
            listOf(Output("切换输入法", "§picker"), Output("设置", "§settings"))
        "symbols" -> listOf(Output("数字", "§num"), Output("符号", "§sym"), Output("学术键盘", "§sci"), Output("文字编辑", "§cursor"), Output("剪贴板", "§clip"))
        "more_symbols" -> listOf(Output("全部符号", "§sym"), Output("数学", "§math"))
        "sci" -> listOf(Output("全部符号", "§sym"))
        "enter" -> listOf(Output("换行", "§newline"), Output(editor.enterActionName(), "§action"))
        "shift" -> listOf(Output("大写锁定", "§caps"))
        else -> key.long.map { Output(it.label, "§" + it.text) }
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
            t == "§sci" -> onFunction(Key(fn = "sci"))
            t == "§math" || t == "§数学" -> showPanel(Panel.MATH)
            t == "§cursor" -> showPanel(Panel.CURSOR)
            t == "§clip" -> showPanel(Panel.CLIPBOARD)
            t == "§newline" -> editor.type("\n", pairable = false)
            t == "§action" -> editor.performEnter()
            t == "§caps" -> { shift = 2; keyboard.invalidate() }
        }
    }

    override fun onCursorMove(dx: Int, dy: Int) {
        var moved = false
        repeat(abs(dx)) { moved = editor.caretStep(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) || moved }
        repeat(abs(dy)) { moved = editor.caretStep(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN) || moved }
        if (moved) haptics.play(HapticEvent.CURSOR)
    }

    override fun onCursorMode(active: Boolean) {
        if (active) { finishComposing(); haptics.play(HapticEvent.GESTURE) }
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
        val i = langs.indexOf(curLang).coerceAtLeast(0)
        setLanguage(langs[(i + direction + langs.size) % langs.size])
    }

    private fun setLanguage(id: String) {
        finishComposing()
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
        if (composing) rimeKey(RimeBridge.XK_BackSpace) else editor.deleteBackward()
    }

    override fun onBackspaceGesture(action: String) {
        if (action == "clear") clearAll() else undo()
    }

    override fun onPress(key: Key) {
        haptics.play(when (key.fn) {
            null -> HapticEvent.LETTER
            "space" -> HapticEvent.SPACE
            "backspace" -> HapticEvent.BACKSPACE
            "enter" -> HapticEvent.ENTER
            else -> HapticEvent.FUNCTION
        })
        haptics.click(key.fn)
    }

    // ---- the bar's listener -------------------------------------------------------------

    override fun onTool(id: String) {
        val target = when (id) {
            "mic" -> Panel.VOICE
            "ai" -> Panel.AI
            "cursor" -> Panel.CURSOR
            "clipboard" -> Panel.CLIPBOARD
            "quick" -> Panel.QUICK
            "emoji" -> Panel.EMOJI
            "symbols" -> Panel.SYMBOLS
            else -> null
        }
        // a second tap on the same button closes its panel (or its query)
        if (target != null && panel == target && sink == null) { showPanel(Panel.LETTERS); return }
        when (id) {
            "math" -> { finishComposing(); showPanel(if (panel == Panel.MATH) Panel.LETTERS else Panel.MATH) }
            "undo" -> undo()
            "settings" -> openSettings()
            "translate" -> if (sink == Sink.TRANSLATE) closeSink() else openSink(Sink.TRANSLATE)
            else -> if (target != null) { if (sink != null) dropSink(); showPanel(target) }
        }
    }

    override fun onCandidate(index: Int) {
        if (index < 0 && sink == Sink.AI && !composing) { runInstruction(); return }
        if (index < 0 && sink == Sink.EMOJI && !composing) { searchResults.getOrNull(-1 - index)?.let { emoji.type(it) }; return }
        if (index < 0) {
            // an association: commit it and suggest what follows
            val word = predictions.getOrNull(-1 - index) ?: return
            val run = editor.deletedRun.toList()
            editor.type(word, pairable = false, syllables = run.takeIf { it.size == word.count { c -> isCjk(c) } })
            predictions = predictor.suggest(editor.textBefore(20).ifEmpty { word })
            predictionLabel = ""
            syncBar()
            return
        }
        if (!RimeEngine.ready) return
        zh.track(RimeBridge.preedit())
        RimeBridge.selectCandidateGlobal(index)
        flushRime()
    }

    override fun candidateOptions(index: Int): List<Output> {
        val word = RimeBridge.candidates(index + 1).getOrNull(index) ?: return emptyList()
        val shown = if (word.codePointCount(0, word.length) > 6) word.substring(0, word.offsetByCodePoints(0, 5)) + "…" else word
        return listOf(Output("取消", "§cancel"), Output("不再优先「$shown」", "§forget"))
    }

    override fun onCandidateOption(index: Int, option: Output) {
        if (option.text != "§forget") return
        val ok = RimeBridge.deleteCandidate(index)
        flushRime()
        notice(if (ok) "已删除学习记录，下次不再排在前面" else "这个词没有学习记录")
    }

    override fun onFeedback(kind: String) {
        haptics.play(when (kind) { "popup" -> HapticEvent.POPUP; "press" -> HapticEvent.LETTER; "cancel" -> HapticEvent.OPTION; else -> HapticEvent.GESTURE })
    }

    override fun onNumMode(mode: String) = onFunction(Key(fn = if (mode == "sci") "sci" else "simple"))

    /** 点拼音改拼音: maps a tap in the preedit to a position in the typed letters and moves Rime's caret there. */
    override fun onPreeditTap(index: Int) {
        if (!composing) return
        val j = zh.inputPosition(RimeBridge.preedit(), RimeBridge.input(), index)
        if (j < 0) return
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

    override fun onExpand() {
        showPanel(if (panel == Panel.CANDIDATES) Panel.LETTERS else Panel.CANDIDATES)
    }

    override fun onClipChip(text: String) {
        clipboard.paste(text)
        syncBar()
    }

    override fun onDismissSuggestions() {
        if (sink != null) { closeSink(); return }
        predictions = emptyList()
        clipboard.dismissChip()
        syncBar()
    }

    /** The only tappable status: Chinese failed to start, so try again. */
    override fun onMessageTap() {
        if (RimeEngine.state != RimeEngine.State.FAILED) return
        haptics.play(HapticEvent.FUNCTION)
        RimeEngine.redeploy(this)
    }

    override fun onMathFormat(format: String) { prefs.put(Prefs.MATH_FORMAT, format) }
    override fun onResultChip(text: String) { editor.type(text, pairable = false); bar.showResult(null) }

    // ---- sinks: the keys type into a query of the keyboard's own ----------------------------

    override fun openSink(sink: Sink, initial: String) {
        finishComposing()
        val selection = if (sink == Sink.TRANSLATE) editor.selectedText()?.takeIf { it.isNotBlank() } else null
        if (this.sink != null) dropSink()
        query.setLength(0)
        query.append(selection ?: initial)
        haptics.play(HapticEvent.FUNCTION)
        showPanel(Panel.LETTERS)
        this.sink = sink
        bar.activeTool = when (sink) { Sink.EMOJI -> "emoji"; Sink.TRANSLATE -> "translate"; Sink.AI -> "ai" }
        if (sink == Sink.TRANSLATE) translate.onOpened(selection)
        syncBar()
    }

    /** Closes the query; where it came from opens again (the emoji panel after 查找, the AI panel after 输入指令). */
    override fun closeSink(to: Panel?) {
        val s = sink ?: return
        dropSink()
        showPanel(to ?: when (s) { Sink.EMOJI -> Panel.EMOJI; Sink.AI -> Panel.AI; Sink.TRANSLATE -> Panel.LETTERS })
    }

    /** Forgets the query without changing the panel. */
    private fun dropSink() {
        val s = sink ?: return
        sink = null
        if (composing) RimeBridge.clearComposition()
        zh.reset()
        query.setLength(0)
        if (s == Sink.TRANSLATE) translate.onClosed()
    }

    /** ▶ 执行 or ↵ with an instruction typed: back to the AI panel, which runs it. */
    private fun runInstruction() {
        val instruction = query.toString().trim()
        dropSink()
        ai.runInstruction(instruction)
    }

    override fun openSettings(page: String?) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (page != null) putExtra("page", page) })
    }

    private companion object {
        /** 运算符列 of the standard number pad: five on screen, the rest by scrolling. */
        val NUM_OPS = listOf("+", "−", "×", "÷", "=", "%", "(", ")", "/", ",", "±")
        val DIRS = listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT)
        const val VOWELS = "аеёиоуыэюя"
        /** Candidates fetched for the bar: more than fit, so a swipe finds more without a pause. */
        const val CANDIDATES_ON_BAR = 60
        val PASSWORDS = setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val ADDRESSES = setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    }
}
