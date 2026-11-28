package com.kk.shurufa.ime

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
import com.kk.shurufa.keyboard.EditPanel
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyboardLayout
import com.kk.shurufa.keyboard.KeyboardListener
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.MathPanel
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PopupOverlay
import com.kk.shurufa.keyboard.QuickPanel
import com.kk.shurufa.keyboard.SheetHandle
import com.kk.shurufa.keyboard.FloatPills
import com.kk.shurufa.keyboard.FrameRate
import com.kk.shurufa.keyboard.SharkLife
import com.kk.shurufa.keyboard.TouchModel
import com.kk.shurufa.online.AiBudget
import com.kk.shurufa.online.Llm
import com.kk.shurufa.online.TranslateService
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import com.kk.shurufa.settings.KeyOverrides
import com.kk.shurufa.settings.SettingsActivity
import kotlin.math.abs

/**
 * The input method. It owns the window, decides which panel fills the area under the bar and
 * what the bar shows, routes keys (Chinese through Rime, the rest straight to the field), and
 * hosts the tools — emoji, clipboard, translation, AI, voice, symbols, numbers — each kept in a
 * class of its own. The opened candidates are [CandidatePanel], how panels change places is
 * [PanelMotion], what a field is [Fields], what the letter keys carry [LetterKeys].
 * Everything written into the field goes through [Editor].
 */
class KkImeService : InputMethodService(), Host, KeyboardListener, CandidateBar.Listener {

    override val context: Context get() = this
    override val prefs by lazy { Prefs(this) }
    override val editor by lazy { Editor(this, prefs).also { it.stats = TypingStats.get(this) } }
    override lateinit var haptics: Haptics
        private set
    override lateinit var kbTheme: KbTheme
        private set
    override lateinit var overlay: PopupOverlay
        private set
    override var panel = Panel.LETTERS
        private set
    override val query = StringBuilder()
    override var queryCaret = -1
    /** Puts [s] into the query where its caret is. */
    private fun queryType(s: String) {
        val c = if (queryCaret in 0 until query.length) queryCaret else -1
        if (c < 0) query.append(s) else { query.insert(c, s); queryCaret = c + s.length }
    }
    /** Backspace in the query: the character before its caret. */
    private fun queryBack() {
        val c = if (queryCaret in 0..query.length) queryCaret else query.length
        if (c == 0) return
        val from = query.offsetByCodePoints(c, -1)
        query.delete(from, c)
        if (queryCaret >= 0) queryCaret = from
    }
    override var sink: Sink? = null
        private set

    private lateinit var repo: LayoutRepo
    private lateinit var predictor: Predictor
    private lateinit var zhMemory: ZhMemory
    private lateinit var zh: ZhComposer
    private val translateService by lazy { TranslateService(prefs) }
    private lateinit var emoji: EmojiFeature
    private lateinit var clipboard: ClipboardFeature
    private lateinit var translate: TranslateFeature
    private lateinit var ai: AiFeature
    private lateinit var voice: VoiceFeature
    private lateinit var symbols: SymbolsFeature
    private lateinit var numbers: NumbersFeature
    private val handler = Handler(Looper.getMainLooper())

    // views of the current input view
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var bar: CandidateBar
    private lateinit var content: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var candidates: CandidatePanel
    private lateinit var symbolPanel: View
    private lateinit var editPanel: EditPanel
    private lateinit var quickPanel: QuickPanel
    private lateinit var clipPanel: View
    private lateinit var emojiPanel: View
    private lateinit var aiPanel: View
    private lateinit var voicePanel: View
    private val built get() = ::root.isInitialized

    private var navInset = 0
    /** Panels fading and rising in, and the area easing between their heights. */
    private lateinit var motion: PanelMotion
    /** 临时拉高: the clipboard and the candidate grid can take more of the screen. */
    private var tall = false

    private var lang = "zh"
    private var tempLang: String? = null
    private var shift = 0
    private var autoShift = false
    private var lastShiftTap = 0L
    private var lastSpaceAt = 0L

    private val layouts = HashMap<String, KeyboardLayout>()
    private val letterKeys by lazy { LetterKeys(prefs) }

    /** 拼音编辑: the bar shows the typed letters large, with a caret that can be placed precisely. */
    private var pinyinEdit = false
    private var predictions: List<String> = emptyList()
    /** 俄语联想's word tables. */
    private val wordTables = WordPredictors(this)
    /** Word suggestions make sense in this field (not passwords, addresses, numbers). */
    private var wordsHere = true
    /** What is typed here may be learned from. */
    private var learnHere = true
    /** Neither a password nor an address (by its type or by its hint): sentences start with a capital. */
    private var plainField = true
    /** 云联想, and the continuations at the front of the suggestions now. */
    private val cloud by lazy { CloudPredict(prefs) { syncBar() } }
    private var cloudShown: List<String> = emptyList()
    /** 小K, and its line of the day while it shows. */
    private val kay by lazy { Kay(this, prefs) }
    private var greeting: String? = null
    private var greetingAt = 0L
    /** AI 浮窗, and what it shows now: 云联想's lines or the AI 候选. */
    private lateinit var floats: FloatPills
    private var floatCloud: List<String> = emptyList()
    private var floatCand: String? = null

    /** AI 候选, and whether the bar shows it first now. */
    private val aiCand by lazy { AiCandidate(prefs) { syncBar() } }
    private var aiShown = false

    /** 学习延后: what Rime is taught once it has stayed a few seconds. */
    private val learning = DeferredLearning()

    /** Taps may teach the touch model (not in 临时模式 or passwords). */
    private var tapLearn = true

    /** A suggestion was just picked and put a space after itself. */
    private var autoSpaced = false
    /** 自动纠错: the latest correction, which the next backspace (or the 原 chip) undoes. */
    /**
     * An automatic change just made: [word] + [sep] is before the caret where [original] + [sep]
     * was typed. [isWord]: a word corrected (自动纠错), the rest are 双击空格, 省略号, 半角标点 and
     * 盘古之白. The next backspace puts back what was typed.
     */
    private class Correction(val word: String, val original: String, val sep: String, val isWord: Boolean = true)

    /** Notes an automatic change (typed [original], [made] is in the field instead). */
    private fun madeAuto(made: String, original: String) {
        if (made != original) corrected = Correction(made, original, "", isWord = false)
    }
    private var corrected: Correction? = null
    private var predictionLabel = ""
    private var searchResults: List<String> = emptyList()
    private var searchFor = ""
    /** A notice raised before the bar existed (a layout file that failed to load). */
    private var pendingNotice: String? = null

    private val rimeListener: (RimeEngine.State) -> Unit = { onRimeState(it) }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        FrameRate.window = window.window
        haptics = Haptics(this, prefs)
        motion = PanelMotion(prefs) { resources.displayMetrics.density }
        repo = LayoutRepo(this, overrides = { KeyOverrides.load(prefs, it) })
        zhMemory = ZhMemory(this).also { if (prefs.zhPredictLearn) it.load() }
        predictor = Predictor(this, zhMemory)
        learning.onTaught = { i -> if (prefs.zhPredictLearn && i.before.isNotEmpty()) zhMemory.learn(i.before, i.text) }
        zh = ZhComposer(this, prefs).also { it.preload() }
        if (prefs.zhPredict) predictor.load()
        if (prefs.packPoetry) predictor.loadPoetry()
        symbols = SymbolsFeature(this)
        numbers = NumbersFeature(this) { bar }
        emoji = EmojiFeature(this).also { it.load(); it.maybeUpdate() }
        clipboard = ClipboardFeature(this).also { it.register() }
        translate = TranslateFeature(this, translateService)
        ai = AiFeature(this, kay)
        translate.ai = ai
        voice = VoiceFeature(this, translateService)
        prefs.sp.registerOnSharedPreferenceChangeListener(settingsListener)
        prefs.state.registerOnSharedPreferenceChangeListener(stateListener)
        RimeEngine.addListener(rimeListener)
        RimeEngine.start(this)
        lang = prefs.languages.firstOrNull() ?: "zh"
    }

    override fun onDestroy() {
        FrameRate.window = null
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
        // the recognizer's models are by far the biggest thing held; they load again when needed.
        // Only when memory is short: UI_HIDDEN (20) is above RUNNING_LOW (10) on the same scale, and
        // it comes every time the keyboard goes away, which is no reason to read 6 MB again later.
        @Suppress("DEPRECATION")
        val short = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (short) {
            voice.trimMemory()
            if (sink != Sink.TRANSLATE) translateService.trimMemory()
            // next come the word tables (the Russian ones are about 6 MB): those of languages not
            // in use now are let go, what they learned saved; they load again on first use
            wordTables.trim(keep = curLang)
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onSetting(key) }

    /** Settings that change how the keyboard is built rebuild it; every other one applies live. */
    private fun onSetting(key: String?) {
        if (key == null) return
        if (key.startsWith("hap_")) { haptics.invalidate(); return }
        // 小K's pace: read here when it changes, not on every frame it is drawn
        if (key.startsWith("kay_")) SharkLife.tune = prefs.kayTune()
        if (Prefs.rebuildsKeyboard(key)) {
            layouts.clear(); numbers.math = null
            if (built) setInputView(onCreateInputView())
            return
        }
        if (key == Prefs.MUTED_STAMP) { if (RimeEngine.ready) restartRimeEngine(); return }
        when (key) {
            Prefs.ZH_EMOJI, Prefs.ZH_TRAD -> applyRimeOptions()
            Prefs.ZH_PREDICT -> if (prefs.zhPredict) predictor.load() else { predictions = emptyList(); syncBar() }
            Prefs.ZH_PREDICT_LEARN -> if (prefs.zhPredictLearn) zhMemory.load()
            Prefs.ZH_CORRECTION, Prefs.GRAMMAR -> RimeEngine.redeploy(this)
            Prefs.TOUCH_ADAPT -> if (built) keyboard.touch = if (prefs.touchAdapt) touchModel else null
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
        if (key == Prefs.INCOGNITO) applyIncognito()
    }

    /**
     * What may be learned and recorded in this field: never from passwords, addresses or fields
     * that ask not to be learned from, and nothing at all in 临时模式.
     */
    private fun applyPrivacy(info: EditorInfo, kind: String? = Fields.kind(info)) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        plainField = kind == null
        val isPassword = kind == "password"
        val isAddress = kind == "address"
        val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 || prefs.incognito
        editor.recording = prefs.typingStats && !isPassword && !isAddress && !noLearning
        editor.secret = isPassword
        editor.app = info.packageName.orEmpty()
        wordsHere = cls == InputType.TYPE_CLASS_TEXT && !isPassword && !isAddress &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
        learnHere = wordsHere && !noLearning
        tapLearn = !noLearning && !isPassword
        if (built) keyboard.learnTaps = tapLearn
    }

    /** 临时模式 on or off: learning stops or resumes everywhere, and the colors change. */
    private fun applyIncognito() {
        applyRimeOptions()
        if (built) setInputView(onCreateInputView())
        currentInputEditorInfo?.let { applyPrivacy(it) }
        if (prefs.incognito) { learning.dropAll(); clipboard.dismissChip() }
        notice(if (prefs.incognito) "临时模式：输入的内容不学习、不记录" else "已退出临时模式")
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
        motion.forgetGhost()
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
        // 按住工具栏拖动: with a resizable panel open (剪贴板, the opened candidates), the bar works as its grab bar too
        bar.onVerticalDrag = { phase, rawY, v ->
            val h = when (panel) { Panel.CLIPBOARD -> clipboard.handle; Panel.CANDIDATES -> candidates.handle; else -> null }
            if (h != null) when (phase) { 0 -> h.begin(rawY); 1 -> h.drag(rawY); else -> h.end(v) }
        }
        content = FrameLayout(this)
        motion.content = content
        keyboard = newKeyboard().also { it.touch = if (prefs.touchAdapt) touchModel else null }
        keyboard.learnTaps = tapLearn
        candidates = CandidatePanel(this, toneFilter, newHandle(),
            pick = { i -> pickZh(i) }, options = { i -> zhOptions(i) }, onOption = { i, o -> zhOption(i, o) }, onTone = { t -> onToneFilter(t) })
        symbolPanel = symbols.build()
        buildEditPanel()
        numbers.build()
        voicePanel = voice.build()
        aiPanel = ai.build()
        clipPanel = clipboard.build { newHandle() }
        emojiPanel = emoji.build()
        quickPanel = QuickPanel(this, prefs).also { it.theme = kbTheme; it.onTile = { id -> onQuickTile(id) }; it.onPress = { haptics.play(HapticEvent.LETTER) } }
        column.addView(translate.build(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        floats = FloatPills(this, prefs).also {
            it.theme = kbTheme; it.visibility = View.GONE
            it.onFeedback = { haptics.play(HapticEvent.OPTION) }
            it.onPick = { i -> floatCand?.let { commitAiCandidate() } ?: floatCloud.getOrNull(i)?.let { p -> pickCloud(p) } }
        }
        root.addView(floats, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // the tabs stand on the keyboard's top edge, whatever its height
        column.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val lp = floats.layoutParams as FrameLayout.LayoutParams
            val m = bottom - top
            if (lp.bottomMargin != m) { lp.bottomMargin = m; floats.layoutParams = lp }
        }
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
        var reported = if (motion.reportH > 0) motion.reportH else shown
        val sheet = if (panel == Panel.CLIPBOARD) clipboard.handle else if (panel == Panel.CANDIDATES) candidates.handle else null
        if (sheet != null && sheet.moving) reported = minOf(reported, baseH + barH + navInset)
        val top = bottom - reported
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        // an open menu takes taps anywhere (a tap outside it closes it)
        if (overlay.sticky) outInsets.touchableRegion.set(0, insetLoc[1], root.width, bottom)
        else outInsets.touchableRegion.set(0, minOf(top, bottom - shown), root.width, bottom)
        // the AI 浮窗 over the app takes its own taps
        if (!overlay.sticky && floats.visibility == View.VISIBLE) for (r in floats.tabRects)
            outInsets.touchableRegion.op(r.left.toInt(), insetLoc[1] + floats.top + r.top.toInt(), r.right.toInt(), insetLoc[1] + floats.top + r.bottom.toInt(), android.graphics.Region.Op.UNION)
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

    /** 落点自适应 for the letter keys, kept across input views. */
    private val touchModel by lazy { TouchModel(prefs.state) }

    override fun newKeyboard() = KeyboardView(this, prefs).also { it.theme = kbTheme; it.listener = this; it.overlay = overlay }

    private fun loadData() {
        try {
            numbers.math = repo.math()
            symbols.categories = repo.symbols()
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

    /** Passwords, web and mail addresses are written in Latin letters: English if it is there, else the first such language. */
    private fun latinLanguage(): String = prefs.languages.firstOrNull { it == "en" }
        ?: prefs.languages.firstOrNull { layout(it).engine != "rime" && it != "ru" } ?: "en"

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Rime's session can have been made anew (a backup's sync): its options with it
        applyRimeOptions()
        // a selection already in the field (made before the keyboard came) counts from the start
        editor.onSelection(info.initialSelStart.coerceAtLeast(0), info.initialSelEnd.coerceAtLeast(0))
        tempLang = null
        predictions = emptyList()
        editor.reset()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (prefs.perAppLanguage && !restarting) prefs.appLanguage(info.packageName ?: "")?.takeIf { it in prefs.languages }?.let { lang = it }
        val kind = Fields.kind(info)
        val isPassword = kind == "password"
        val isAddress = kind == "address"
        if ((isPassword || isAddress) && (isZh || curLang == "ru")) tempLang = latinLanguage()
        // 打字数据: never from passwords, addresses or fields that ask not to be learned from
        applyPrivacy(info, kind)
        autoSpaced = false
        // the field says what goes in it: numbers get the number pad, with the marks that field takes
        numbers.fieldOps = Fields.numberOps(info)
        panel = when {
            cls == InputType.TYPE_CLASS_PHONE -> Panel.NUM
            (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME) && prefs.numberFieldMath -> Panel.NUM
            else -> Panel.LETTERS
        }
        shift = 0; autoShift = false
        // 小K's line of the day: the first time the keyboard opens on a plain, empty text field
        greeting = null
        if (!restarting && panel == Panel.LETTERS && wordsHere && !prefs.incognito && clipboard.pendingChip() == null && kay.greetingDue() &&
            editor.textBefore(1).isEmpty() && editor.textAfter(1).isEmpty()) {
            greeting = kay.line(); kay.markGreeted(line = greeting); greetingAt = SystemClock.uptimeMillis()
            SharkLife.greet(greetingAt)
        }
        if (!prefs.incognito) kay.prefetch()
        SharkLife.saver = prefs.powerSave()
        // back after a long while: it wakes with a start
        if (!restarting && greeting == null) SharkLife.woke(SystemClock.uptimeMillis())
        if (built) { paintNavigationBar(); show(panel) }
        // the app started the field over (web pages do, as they re-render): pinyin being typed stays
        if (restarting && composing && prefs.zhPreeditInline) editor.compose(RimeBridge.input())
        updateAutoCap()
    }

    /** The line of the day goes (a key, a tap, the field changing); [wave]: 小K waves on the space key. */
    private fun dropGreeting(wave: Boolean = false) {
        if (greeting == null) return
        greeting = null
        if (wave && !kayOnSpace()) spaceSay("(・ω・)ﾉ")
        if (built) syncBar()
    }

    // ---- the space key: 小K's little window ----

    private var spaceNote: String? = null
    private var spaceNoteUntil = 0L

    /** 小K says [text] on the space key for a moment. */
    private fun spaceSay(text: String, ms: Long = 2600) {
        if (!built) return
        spaceNote = text; spaceNoteUntil = SystemClock.uptimeMillis() + ms
        keyboard.invalidate()
        keyboard.postDelayed({ if (SystemClock.uptimeMillis() >= spaceNoteUntil) { spaceNote = null; keyboard.invalidate() } }, ms + 20)
    }

    override fun kayOnSpace() = prefs.kayOnSpace && !keyboardPhone
    /** 临时模式 or a password: 小K is there, its fins over its eyes, still and quiet. */
    override fun kayShy() = prefs.incognito || editor.secret

    override fun onKayPat() {
        SharkLife.pat(SystemClock.uptimeMillis())
        haptics.play(HapticEvent.OPTION)
        // on the space key it simply smiles; otherwise it says so there
        if (kayOnSpace()) keyboard.invalidate() else spaceSay(listOf("嘿嘿 (｡>ω<｡)", "再摸一下 (〃∀〃)", "${kay.name}在呢 (・ω・)").random())
        bar.invalidate()
    }

    override fun onGreeting(action: String) {
        val line = greeting
        dropGreeting(wave = action != "chat")
        if (action == "chat") ai.openChat(line)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // leaving the field: what stayed is learned now
        learning.flush(all = true)
        if (sink != null) dropSink()
        stopBackgroundWork()
        editor.selectMode = false
        greeting = null
        if (RimeEngine.ready) RimeBridge.clearComposition()
        zh.reset()
        predictions = emptyList()
        editor.finishComposingText()
        if (built) syncBar()
    }

    /** The keyboard went away without the field finishing (another app came up, the screen went off). */
    override fun onWindowHidden() {
        super.onWindowHidden()
        FrameRate.idle()
        stopBackgroundWork()
        wordTables.saveAll()
        zhMemory.save()
        Background.run { TypingStats.get(this).save() }
    }

    /** Nothing keeps working once the keyboard is out of sight: the microphone stops, a streaming answer is dropped. */
    private fun stopBackgroundWork() {
        if (panel == Panel.VOICE) voice.onClosed()
        ai.cancel()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        editor.onSelection(newSelStart, newSelEnd)
        // a tap in the field (the caret moved by the app, not by typing) puts the line away
        if (greeting != null && SystemClock.uptimeMillis() - greetingAt > 800 && (newSelStart != oldSelStart || newSelEnd != oldSelEnd)) dropGreeting()
        if (built && panel == Panel.CURSOR) syncEditPanel()
    }

    private fun onRimeState(s: RimeEngine.State) {
        if (s == RimeEngine.State.READY) applyRimeOptions()
        syncBar()
    }

    /** Rime's engine built again (its Lua filters read their lists afresh), the pinyin kept. */
    private fun restartRimeEngine() {
        val input = RimeBridge.input()
        RimeBridge.selectSchema("rime_ice")
        applyRimeOptions()
        if (input.isNotEmpty()) RimeBridge.setInput(input)
        flushRime()
    }

    private fun applyRimeOptions() {
        if (!RimeEngine.ready) return
        RimeBridge.setOption("emoji", prefs.zhEmoji)
        RimeBridge.setOption("traditionalization", prefs.zhTraditional)
        // Rime never learns at the moment of a commit (a guard added to its Memory): commits that
        // stay are taught a few seconds later (学习延后), and in 临时模式 not at all
        RimeBridge.setOption("_kk_no_learning", true)
    }

    // ---- panels -------------------------------------------------------------------------

    /** The letter rows with the switches that change them applied; the same list while nothing changed. */
    private fun lettersRows(): List<List<Key>> = letterKeys.rows(curLang, layout(curLang))

    private fun kbHeight(rows: Int): Int {
        val keyH = dp(prefs.keyHeightDp.toFloat())
        return (dp(6f) + dp(prefs.bottomPaddingDp + 2f) + rows * keyH + (rows - 1) * dp(prefs.rowGapDp)).toInt()
    }

    private val barH get() = dp(CandidateBar.HEIGHT_DP).toInt()
    private val baseH get() = kbHeight(lettersRows().size.coerceAtLeast(4))
    /** 学术 is taller than the letters: a tab row, four rows of keys and the bottom row. */
    private fun mathH(): Int {
        val kh = dp(prefs.keyHeightDp.toFloat())
        val g = dp(prefs.rowGapDp)
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
        if (p != Panel.LETTERS) greeting = null
        // 选择 ends with the cursor panel: opened again, it starts as 选择, not 选择中
        if (from == Panel.CURSOR && p != Panel.CURSOR) editor.selectMode = false
        panel = p
        if (p != Panel.LETTERS && p != Panel.CANDIDATES) pinyinEdit = false
        val oldTotal = (if (bar.visibility == View.VISIBLE) bar.height else 0) + content.height
        // the panel that leaves fades out where it is while the new one fades in over it
        val leaving = content.getChildAt(0)
        motion.clearGhost()
        if (from != p && leaving != null) motion.fadeOut(leaving)
        content.removeAllViews()
        motion.stopHeight()
        val base = baseH
        val tallH = tallH()
        val canTall = p == Panel.CLIPBOARD || p == Panel.CANDIDATES
        // 记住符号: back to the symbols only if they were the last panel of the family used
        if (from == Panel.SYMBOLS && p != Panel.SYMBOLS) symbols.lastUsedAt = if (inFamily(p)) 0L else SystemClock.uptimeMillis()
        for (h in listOf(clipboard.handle, candidates.handle)) { h.low = base; h.high = tallH; h.current = if (tall) tallH else base }
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
            Panel.LETTERS -> { keyboardPhone = false; keyboard.setRows(lettersRows()); content.addView(keyboard, fixed) }
            Panel.PHONE -> { keyboardPhone = true; keyboard.setRows(layout("phone").rows); content.addView(keyboard, fixed) }
            Panel.MATH -> {
                if (numbers.math == null) { showPanel(Panel.LETTERS); return }
                numbers.refreshMath(slide = 0)
                content.addView(numbers.mathPanel, fixed)
            }
            Panel.NUM -> { numbers.showNumPad(layout("num").rows); content.addView(numbers.numPad, fixed); numbers.showMoneyChip() }
            Panel.CURSOR -> { editor.refreshSelection(); syncEditPanel(); content.addView(editPanel, fixed) }
            Panel.SYMBOLS -> { symbols.refresh(); content.addView(symbolPanel, fixed) }
            Panel.CLIPBOARD -> { clipboard.refresh(); content.addView(clipPanel, fixed) }
            Panel.QUICK -> { refreshQuick(); content.addView(quickPanel, fixed); if (entering) quickPanel.animateIn() }
            Panel.EMOJI -> { emoji.refresh(); content.addView(emojiPanel, fixed) }
            Panel.AI -> { content.addView(aiPanel, fixed); if (entering) ai.onOpened() }
            Panel.VOICE -> { content.addView(voicePanel, fixed); if (entering) voice.onOpened() }
            Panel.CANDIDATES -> {
                candidates.syncTones()
                val (cands, comments) = zhCandidates(GRID_CANDIDATES)
                candidates.fill(cands, zh.annotations(cands, comments))
                content.addView(candidates.view, fixed)
            }
        }
        if (entering && p != Panel.LETTERS && p != Panel.PHONE && p != Panel.QUICK) motion.animateIn(content.getChildAt(0))
        bar.expanded = p == Panel.CANDIDATES
        bar.activeTool = when (sink) { Sink.EMOJI -> "emoji"; Sink.TRANSLATE -> if (translate.mode == "translate") "translate" else "ai"; null -> toolFor(p) }
        syncBar()
        motion.animateHeight(oldTotal, if (noBar) 0 else barH, root.width, navInset)
    }

    // ---- the bar ------------------------------------------------------------------------

    /**
     * Shows on the bar (and in the AI 浮窗) what the state calls for. What it shows is decided in
     * [syncBarOnly]; the AI requests it may start ([askAiCandidate], [askCloud]) do nothing when what
     * they are for is already waiting or on its way, so syncing again for another reason is free.
     */
    override fun syncBar() {
        if (!built) return
        floatCloud = emptyList(); floatCand = null
        syncBarOnly()
        floats.show(floatCand?.let { listOf(it) } ?: floatCloud)
        // the opened candidates without a composition: back to the letters
        if (panel == Panel.CANDIDATES && !composing) showPanel(Panel.LETTERS)
        if (AiBudget.justPaused) {
            AiBudget.justPaused = false
            notice("AI 服务说请求太多（免费额度可能用完了），云联想先歇 30 分钟")
        }
    }

    private fun syncBarOnly() {
        val s = sink
        val letters = panel == Panel.LETTERS
        when {
            panel == Panel.MATH -> bar.showMath(prefs.mathFormat, simple = false)
            panel == Panel.NUM -> bar.showMath(prefs.mathFormat, simple = true)
            // a query the user opened comes first: it works without the Chinese engine
            s == Sink.TRANSLATE && !composing && letters -> { bar.showToolbar(); translate.sync() }
            greeting != null && s == null && letters && !composing && predictions.isEmpty() -> bar.showGreeting(greeting!!, Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) != null)
            s == Sink.EMOJI && !composing && letters -> showEmojiSearch()
            isZh && RimeEngine.state == RimeEngine.State.DEPLOYING && letters -> bar.showMessage("正在部署雾凇拼音词库，首次约需一分钟…", busy = true)
            isZh && RimeEngine.state == RimeEngine.State.PREPARING && letters -> bar.showMessage("正在准备中文词库…", busy = true)
            isZh && RimeEngine.state == RimeEngine.State.FAILED && letters -> bar.showMessage("中文引擎没有启动 · 点按重试", tappable = true)
            composing && pinyinEdit -> bar.showEdit(RimeBridge.input(), RimeBridge.caretPos(), zh.gaps(RimeBridge.input(), RimeBridge.preedit()))
            composing -> showComposition()
            predictions.isNotEmpty() && letters -> showSuggestions()
            clipboard.pendingChip() != null && letters && !prefs.incognito -> clipboard.chip()?.let { (text, kind) -> bar.showClip(text, kind) }
            else -> bar.showToolbar()
        }
    }

    /** 查找表情: what matches the query, searched again only when it changed. */
    private fun showEmojiSearch() {
        val q = query.toString().trim()
        if (q != searchFor || searchResults.isEmpty()) { searchResults = emoji.search(q); searchFor = q }
        if (q.isEmpty()) bar.showQuery("查找表情", "", "输入中文或英文关键词，比如 开心、cat", null)
        else bar.showPredictions(searchResults, if (searchResults.isEmpty()) "没有找到和「$q」相关的表情" else "查找「$q」· 点按发送")
    }

    /** The pinyin and Rime's candidates, with the AI 候选 second (or in the 浮窗) when it came. */
    private fun showComposition() {
        val pre = RimeBridge.preedit()
        val input = RimeBridge.input()
        zh.track(pre)
        // a page or two at first; more as the row is scrolled toward its end (onMoreCandidates)
        val barKey = input + "\u0000" + pre
        if (barKey != barFor) { barFor = barKey; barCands = CANDIDATES_ON_BAR }
        val (cands, comments) = zhCandidates(barCands)
        askAiCandidate(input, pre, cands.firstOrNull().orEmpty())
        val aiAnswer = aiCand.answerFor(input)
        // in the 浮窗 it leaves the bar as Rime has it
        val floating = prefs.aiPlace == "float"
        if (floating) floatCand = aiAnswer
        val ai = aiAnswer.takeIf { !floating }
        aiShown = ai != null
        val notes = zh.annotations(cands, comments)
        val caret = zh.caretInPreedit(pre, RimeBridge.cursor())
        // second, marked AI: the first stays Rime's, which is what space takes
        if (ai != null) bar.showCandidates(pre, cands.take(1) + ai + cands.drop(1), notes.take(1).ifEmpty { listOf("") } + "AI" + notes.drop(1), caret)
        else bar.showCandidates(pre, cands, notes, caret)
        if (prefs.zhPreeditInline) editor.compose(input)
    }

    /** AI 候选 for this pinyin, after a pause, where it may be asked. */
    private fun askAiCandidate(input: String, pre: String, first: String) =
        aiCand.update(input, pre.split(' ', '\'').count { it.isNotBlank() }, first, { editor.textBefore(20) },
            allowed = learnHere && sink == null && !toneFiltering)

    /** The suggestions after a word, with 云联想's continuations first (or in the 浮窗) when they came. */
    private fun showSuggestions() {
        // read from the field only when it is going to be asked (each read is a call into the app)
        val before = if (prefs.cloudPredict) editor.textBefore(prefs.cloudContext) else ""
        val revert = revertChip && corrected != null
        val cloudHere = predictionLabel.isEmpty() && !revert
        if (cloudHere) askCloud(before)
        cloudShown = if (cloudHere) cloud.answerFor(before) else emptyList()
        if (prefs.aiPlace == "float" && cloudShown.isNotEmpty()) { floatCloud = cloudShown; cloudShown = emptyList() }
        when {
            revert -> bar.showPredictions(predictions, predictionLabel, listOf("原"))
            cloudShown.isEmpty() -> bar.showPredictions(predictions, predictionLabel)
            else -> bar.showPredictions(cloudShown + predictions.filter { it !in cloudShown }, predictionLabel, cloudShown.map { "云" })
        }
    }

    /** 云联想 for the text before the caret, after a pause, where it may be asked. */
    private fun askCloud(before: String) = cloud.update(before, allowed = learnHere && sink == null)

    /** A short notice floating above the keyboard (instead of a toast over the app). */
    override fun notice(text: String) {
        if (built && column.height > 0) overlay.toast(text, column.top.toFloat()) else pendingNotice = text
    }

    // ---- the grab bar, the edit pad, the quick panel ------------------------------------

    /** 面板高度档位: dragging the grab bar resizes the open panel live; it settles on one of two heights. */
    private fun newHandle() = SheetHandle(this, prefs).apply {
        theme = kbTheme
        onFeedback = { k -> haptics.play(if (k == "gesture") HapticEvent.GESTURE else HapticEvent.OPTION) }
        onHeight = { h -> content.getChildAt(0)?.let { v -> v.layoutParams = v.layoutParams.apply { height = h }; v.requestLayout() } }
        onSettled = { t -> tall = t }
        onRest = { root.requestLayout() }
    }

    private fun buildEditPanel() {
        editPanel = EditPanel(this, prefs).apply {
            theme = kbTheme
            labels = prefs.cursorLabels
            onFeedback = { k -> haptics.play(when (k) { "repeat" -> HapticEvent.REPEAT; "touch" -> HapticEvent.FUNCTION; else -> HapticEvent.CURSOR }) }
            onAction = { id -> onEditAction(id) }
        }
    }

    /** A short word that the copy worked, with how much went. */
    private fun copied(text: String?, what: String) {
        if (text == null) return
        haptics.play(HapticEvent.OPTION)
        val n = text.codePointCount(0, text.length)
        notice(if (n <= 12) "$what「${text.replace('\n', ' ')}」" else "$what $n 个字")
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
            in CARET_ACTIONS -> caretAction(id)
            "select_all" -> { editor.contextMenu(android.R.id.selectAll); editor.selectMode = true }
            "backspace" -> { haptics.play(HapticEvent.REPEAT); editor.deleteBackward() }
            "copy" -> { copied(editor.copySelection(cut = false), "已复制"); editor.selectMode = false; editor.collapseSelection() }
            "cut" -> { copied(editor.copySelection(cut = true), "已剪切"); editor.selectMode = false }
            "paste" -> { editor.contextMenu(android.R.id.paste); editor.selectMode = false }
        }
        syncEditPanel()
    }

    /** The caret keys of the edit panel and the academic keyboard (with shift while selecting). */
    private fun caretAction(id: String) {
        when (id) {
            "left" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_RIGHT)
            "up" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_DOWN)
            "word_left" -> editor.sendKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or editor.shiftMeta())
            "word_right" -> editor.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON or editor.shiftMeta())
            "home" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_HOME, editor.shiftMeta())
            "end" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_END, editor.shiftMeta())
        }
    }

    override fun onMoneyChip(number: String, words: String) = numbers.onMoneyChip(number, words)

    /** 日期 · 时间 · 农历: types 雾凇's trigger, whose candidates then fill the bar. */
    private fun rimeTool(code: String) {
        if (!RimeEngine.ready) { notice("中文引擎还没准备好"); return }
        if (!isZh) setLanguage(prefs.languages.firstOrNull { layout(it).engine == "rime" } ?: "zh")
        finishComposing()
        showPanel(Panel.LETTERS)
        RimeBridge.setInput(code)
        flushRime()
    }

    private fun refreshQuick() {
        quickPanel.sections = QuickTiles.sections(prefs, isZh, curLang)
    }

    private fun onQuickTile(id: String) {
        when (id) {
            in QuickTiles.RIME_TOOLS -> rimeTool(QuickTiles.RIME_TOOLS[id]!!)
            "incognito" -> prefs.state.edit().putBoolean(Prefs.INCOGNITO, !prefs.incognito).apply()
            "calc" -> { prefs.put(Prefs.NUM_MODE, "simple"); showPanel(Panel.NUM) }
            "trad" -> prefs.put(Prefs.ZH_TRAD, !prefs.zhTraditional)
            "emoji" -> prefs.put(Prefs.ZH_EMOJI, !prefs.zhEmoji)
            "predict" -> if (isZh) prefs.put(Prefs.ZH_PREDICT, !prefs.zhPredict) else prefs.put(Prefs.WORD_PREDICT, !prefs.wordPredict)
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
            queryType(commit)
        } else if (commit.isNotEmpty()) {
            val syl = zh.syllables()
            var out = if (prefs.pinyinMode && commit.any { isCjk(it) }) zh.tones.convert(commit, syl) else commit
            if (prefs.pinyinMode && out != commit) {
                // 拼音模式 separates consecutive words with a space
                val prev = editor.textBefore(1)
                if (prev.isNotEmpty() && prev[0].isLetterOrDigit() && !isCjk(prev[0])) out = " $out"
            }
            zh.reset()
            // learned later if it stays (a slip deleted right away teaches nothing)
            if (learnHere && commit.any { isCjk(it) }) learning.committed(syl.joinToString(""), commit, before = editor.textBefore(8))
            punct.restoreFullWidth(out)
            val spaced = pangu(out)
            editor.type(spaced, pairable = false, syllables = if (out == commit) syl else null)
            corrected = null; madeAuto(spaced, out)
            predictions = if (out.any { isCjk(it) }) zhSuggestions(fallback = out) else emptyList()
            predictionLabel = ""
        } else if (prefs.zhPreeditInline && RimeBridge.input().isEmpty()) editor.finishComposingText()
        if (RimeBridge.input().isEmpty()) { zh.reset(); pinyinEdit = false; aiCand.clear(); aiShown = false }
        // the opened grid first: it fetches the most, and the bar then reads from what it fetched
        if (panel == Panel.CANDIDATES && composing) {
            val (c, comments) = zhCandidates(GRID_CANDIDATES)
            candidates.fill(c, zh.annotations(c, comments))
        }
        syncBar()
        if (panel == Panel.CANDIDATES && !composing) showPanel(Panel.LETTERS)
    }

    /**
     * 删除后联想: the deleted characters' syllables (what was typed for them) are looked up again and
     * offered as suggestions; nothing is put back into the input, so typing on simply ignores them.
     * Without a record of the syllables, suggestions follow the text that is left.
     */
    private fun suggestAfterDelete() {
        predictions = emptyList(); predictionLabel = ""
        autoSpaced = false
        if (!isZh) { suggestWords(); return }
        if (panel != Panel.LETTERS) return
        val run = editor.deletedRun
        if (prefs.backspaceReselect && run.isNotEmpty() && RimeEngine.ready) {
            predictions = RimeBridge.query(run.joinToString(""), 24).filter { it.isNotEmpty() }.distinct()
            predictionLabel = "按原拼音 " + run.joinToString(" ")
        } else predictions = zhSuggestions()
    }

    /** 中文联想 after what is before the caret ([fallback] where the field shows nothing), unless off or in 拼音模式. */
    private fun zhSuggestions(fallback: String = ""): List<String> =
        if (prefs.zhPredict && !prefs.pinyinMode) predictor.suggest(editor.textBefore(20).ifEmpty { fallback }) else emptyList()

    // ---- the field ----------------------------------------------------------------------

    /** 上滑清空: drops the pinyin while composing, otherwise the whole field (undoable). */
    private fun clearAll() {
        if (composing) { RimeBridge.clearComposition(); flushRime(); return }
        if (!editor.clearAll()) return
        learning.clearedAll()
        predictions = emptyList()
        syncBar()
        updateAutoCap()
    }

    private fun undo() {
        learning.undone()
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
        val caps = panel == Panel.LETTERS && !isZh && prefs.autoCap && cls == InputType.TYPE_CLASS_TEXT && plainField && run {
            // through the editor's read of the field, which the rest of the key's work shares
            if (currentInputConnection == null) return@run false
            val before = editor.textBefore(3)
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

    /** Whether the letters' view shows the phone pad (its space is plain 空格). */
    private var keyboardPhone = false
    override fun shiftState() = shift
    override fun spaceLabel(): String {
        // the phone pad's space is just 空格; the letters keep their language's name even while they
        // fade out under another panel (that panel is already the current one by then)
        if (keyboardPhone) return "空格"
        val name = layout(curLang).label
        val note = spaceNote?.takeIf { SystemClock.uptimeMillis() < spaceNoteUntil }
        return when {
            prefs.incognito -> "$name · 临时"
            note != null -> note
            else -> name
        }
    }

    override fun onOutput(key: Key, output: Output) {
        // what was read of the field lasts for one key at most (the app may have changed it since)
        editor.dirty()
        corrected = null
        dropGreeting()
        if (panel == Panel.MATH) { numbers.mathOut(output); return }
        val t = output.text
        val isMain = output === key.main
        if (sink != null && panel == Panel.LETTERS) {
            // a query: Chinese letters still go through Rime, whose words then join the query
            if (isZh && isMain && t.length == 1 && t[0] in 'a'..'z' && rimeKey(t[0].code)) return
            finishComposing()
            queryType(if (isMain && key.isLetter && shift > 0) t.uppercase() else t)
            syncBar()
            return
        }
        if (panel == Panel.NUM) { numbers.typed(t); return }
        predictions = emptyList()
        editor.deletedRun.clear()
        if (isZh && panel == Panel.LETTERS) {
            if (isMain && t.length == 1 && (t[0] in 'a'..'z' || (t == "'" && composing))) {
                if (rimeKey(t[0].code)) return
                if (!RimeEngine.ready) { editor.type(t); return }
            }
            if (composing && t == "@") {
                // the letters before @ were an address, not pinyin: they go in as typed
                val raw = RimeBridge.input()
                RimeBridge.clearComposition(); zh.reset(); flushRime()
                editor.type(raw + t)
                syncBar(); return
            }
            finishComposing()
            if (ellipsis(t)) { syncBar(); return }
            val half = punct.smartPunct(t)
            val made = pangu(half)
            editor.type(made)
            // a mark made half-width is not put back by backspace: deleted and typed again soon, it is the full one
            if (half == t) madeAuto(made, t)
            // 3, stays half-width before more digits; Chinese after it turns it back (3，好的)
            punct.typed(half, t)
            syncBar()
            return
        }
        val typed = if (isMain && key.isLetter && shift > 0) t.uppercase() else t
        if (ellipsis(typed)) { autoSpaced = false; updateAutoCap(); if (panel == Panel.LETTERS) { suggestWords(); syncBar() }; return }
        val wordChar = typed.isNotEmpty() && typed.all { WordPredictor.isWordChar(it) }
        val fix = if (!wordChar && typed.length == 1 && typed[0] in ",.!?;:)…\"") autocorrect() else null
        if (!wordChar) learnWordBefore()
        if (autoSpaced && typed.length == 1 && typed[0] in ",.!?;:)…" && editor.textBefore(1) == " ") {
            // a picked word put a space after itself: punctuation goes before that space
            editor.replaceBefore(1, if (typed[0] == ')') typed else "$typed ")
        } else pangu(typed).let { made -> editor.type(made); madeAuto(made, typed) }
        if (fix != null) corrected = Correction(fix.first, fix.second, typed)
        autoSpaced = false
        if (isMain && key.isLetter && shift == 1) { shift = 0; autoShift = false; keyboard.invalidate() }
        updateAutoCap()
        if (panel == Panel.LETTERS) { suggestWords(); syncBar() }
    }

    /** 盘古之白, 双击句号, 数字后的半角标点. */
    internal val punct by lazy { Punctuation(editor, prefs) { wordsHere } }
    private fun pangu(t: String) = punct.pangu(t)

    /** 双击句号; true when the tap made an ellipsis (which the next backspace takes back). */
    private fun ellipsis(t: String): Boolean {
        val (made, typed) = punct.ellipsis(t) ?: return false
        corrected = Correction(made, typed, "", isWord = false)
        return true
    }

    override fun onFunction(key: Key) {
        // what was read of the field lasts for one key at most (the app may have changed it since)
        editor.dirty()
        dropGreeting()
        when (key.fn) {
            "backspace" -> {
                predictions = emptyList()
                when {
                    composing -> rimeKey(RimeBridge.XK_BackSpace)
                    sink != null -> { queryBack(); syncBar() }
                    revertCorrection() -> {}
                    else -> {
                        punct.deleting()
                        learning.deleting { n -> editor.textBefore(n) }
                        editor.deleteBackward(); updateAutoCap(); suggestAfterDelete(); if (panel == Panel.NUM) numbers.showMoneyChip(); syncBar()
                    }
                }
            }
            "space" -> {
                if (composing) { rimeKey(RimeBridge.XK_space); return }
                if (sink != null) { queryType(" "); syncBar(); return }
                predictions = emptyList()
                val fix = autocorrect()
                learnWordBefore()
                autoSpaced = false
                val now = SystemClock.uptimeMillis()
                val before = editor.textBefore(2)
                // 双击空格: a full stop after a word
                if (prefs.doubleSpacePeriod && !isZh && panel == Panel.LETTERS && now - lastSpaceAt < 600 &&
                    before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    editor.replaceBefore(1, ". ")
                    corrected = Correction(". ", "  ", "", isWord = false)
                } else {
                    editor.type(" ")
                    corrected = fix?.let { Correction(it.first, it.second, " ") }
                }
                lastSpaceAt = now
                updateAutoCap()
                suggestWords()
                syncBar()
            }
            "enter" -> when {
                composing -> rimeKey(RimeBridge.XK_Return)
                sink == Sink.TRANSLATE -> translate.enter()
                sink == Sink.EMOJI -> searchResults.firstOrNull()?.let { emoji.type(it) }
                else -> {
                    // 发送: 小K jumps for the message that went out
                    val info = currentInputEditorInfo
                    if (info != null && (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND &&
                        (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) { SharkLife.sent(SystemClock.uptimeMillis()); keyboard.invalidate(); bar.invalidate() }
                    predictions = emptyList(); editor.performEnter(); updateAutoCap(); syncBar()
                }
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
                showPanel(if (symbols.recentlyUsed()) Panel.SYMBOLS else numbers.panel())
            }
            "sci" -> { prefs.put(Prefs.NUM_MODE, "sci"); showPanel(Panel.MATH) }
            "simple" -> { prefs.put(Prefs.NUM_MODE, "simple"); showPanel(Panel.NUM) }
            "num" -> showPanel(numbers.panel())
            "more_symbols" -> showPanel(Panel.SYMBOLS)
            "math" -> { finishComposing(); showPanel(Panel.MATH) }
            "abc" -> { editor.selectMode = false; showPanel(Panel.LETTERS) }
            "collapse" -> { toneFilter.reset(); showPanel(Panel.LETTERS) }
            "reinput" -> { RimeBridge.clearComposition(); flushRime(); showPanel(Panel.LETTERS) }
            "undo" -> undo()
            "lock" -> symbols.toggleLock()
            in CARET_ACTIONS -> caretAction(key.fn.orEmpty())
            "select_all" -> editor.contextMenu(android.R.id.selectAll)
            "copy" -> { copied(editor.copySelection(cut = false), "已复制"); editor.selectMode = false }
            "cut" -> { copied(editor.copySelection(cut = true), "已剪切"); editor.selectMode = false }
            "paste" -> editor.contextMenu(android.R.id.paste)
            "next_slot" -> editor.nextSlot()
        }
    }

    override fun longOptions(key: Key): List<Output> = longOptionRows(key).flatten()

    /** 长按选项: a function key's own list, or what [LetterKeys.longOptionRows] offers for a key that types. */
    override fun longOptionRows(key: Key): List<List<Output>> {
        key.fn?.let { return listOf(fnOptions(it, key)) }
        return letterKeys.longOptionRows(key, panel, shift, curLang) { lettersRows() }
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
        if (panel == Panel.SYMBOLS) { showPanel(numbers.panel()); return null }
        switchLanguage(direction)
        return layout(curLang).label
    }

    private fun switchLanguage(direction: Int) {
        val langs = prefs.languages
        if (langs.size < 2) return
        val i = langs.indexOf(curLang).coerceAtLeast(0)
        setLanguage(langs[(i + direction + langs.size) % langs.size], direction)
    }

    /** [direction] is where the new language comes from (the swipe's or the globe key's); 0 for none. */
    private fun setLanguage(id: String, direction: Int = 0) {
        finishComposing()
        tempLang = null
        lang = id
        shift = 0; autoShift = false
        predictions = emptyList()
        currentInputEditorInfo?.packageName?.let { if (prefs.perAppLanguage) prefs.setAppLanguage(it, id) }
        // the whole keyboard moves with the finger, as the space label does: swiped left, the
        // old keys leave to the left and the new ones come in from the right
        if (panel != Panel.LETTERS) showPanel(Panel.LETTERS) else { keyboard.setRows(lettersRows(), slide = direction.coerceIn(-1, 1)); syncBar() }
        updateAutoCap()
    }

    override fun onBackspaceRepeat() {
        // it buzzes for what it deletes: once the field is empty, a held key goes quiet
        val deleted = if (composing) { rimeKey(RimeBridge.XK_BackSpace); true } else editor.deleteBackward()
        if (deleted) haptics.play(HapticEvent.REPEAT)
    }

    override fun onBackspaceGesture(action: String) {
        if (action == "clear") clearAll() else undo()
    }

    override fun onPress(key: Key) {
        // 小K feels the keys: quick typing, a run of deletes
        val now = SystemClock.uptimeMillis()
        if (key.fn == "backspace") SharkLife.delete(now) else SharkLife.key(now)
        if (key.fn == "backspace" || key.fn == null) keyboard.invalidate()
        haptics.play(when (key.fn) {
            null -> HapticEvent.LETTER
            "space" -> HapticEvent.SPACE
            "backspace" -> HapticEvent.BACKSPACE
            "enter" -> HapticEvent.ENTER
            else -> HapticEvent.FUNCTION
        })
        haptics.click(key.fn)
    }

    // ---- the back key: a layer closes before the keyboard does ----

    private var backTaken = false

    /** What the back key closes first, if anything is open over the letters; true when it closed something. */
    private fun backOneLayer(): Boolean {
        if (!built || !isInputViewShown) return false
        if (overlay.sticky) { overlay.dismissSticky(); return true }
        if (sink != null) { closeSink(); return true }
        if (panel in BACK_CLOSES) {
            if (panel == Panel.CANDIDATES) toneFilter.reset()
            showPanel(Panel.LETTERS); return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && backOneLayer()) { backTaken = true; return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && backTaken) { backTaken = false; return true }
        return super.onKeyUp(keyCode, event)
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
        // …the same for the lit button of an open strip: ✦ closes 改写 and 聊天 as 译 closes 翻译
        if (sink != null && id == bar.activeTool && id != "translate") { closeSink(); return }
        when (id) {
            "math" -> { finishComposing(); showPanel(if (panel == Panel.MATH) Panel.LETTERS else Panel.MATH) }
            "undo" -> undo()
            "settings" -> openSettings()
            "translate" -> if (sink == Sink.TRANSLATE && translate.mode == "translate") closeSink() else { translate.nextMode = "translate"; openSink(Sink.TRANSLATE) }
            else -> if (target != null) { if (sink != null) dropSink(); showPanel(target) }
        }
    }

    override fun onCandidate(index: Int) {
        // what was read of the field lasts for one key at most (the app may have changed it since)
        editor.dirty()
        if (index < 0 && sink == Sink.EMOJI && !composing) { searchResults.getOrNull(-1 - index)?.let { emoji.type(it) }; return }
        if (index < 0 && cloudShown.isNotEmpty()) {
            // the bar shows the cloud's continuations, then the local suggestions without repeats
            val i = -1 - index
            if (i < cloudShown.size) { pickCloud(cloudShown[i]); return }
            val word = predictions.filter { it !in cloudShown }.getOrNull(i - cloudShown.size) ?: return
            cloudShown = emptyList()
            onCandidate(-1 - predictions.indexOf(word))
            return
        }
        if (index < 0 && !isZh) { pickWord(-1 - index); return }
        if (index < 0) {
            // an association: commit it and suggest what follows
            val word = predictions.getOrNull(-1 - index) ?: return
            val run = editor.deletedRun.toList()
            // 联想记忆: an association picked is what this person writes after this
            if (learnHere) learning.committed("", word, before = editor.textBefore(8))
            editor.type(pangu(word), pairable = false, syllables = run.takeIf { it.size == word.count { c -> isCjk(c) } })
            predictions = zhSuggestions(fallback = word)
            predictionLabel = ""
            syncBar()
            return
        }
        // the bar's second word may be the AI's reading of the whole pinyin
        val r = barToRime(index) ?: return commitAiCandidate()
        pickZh(r)
    }

    /** Picks Rime's candidate at [index] of what is shown (the grid's numbering, the bar's without AI). */
    private fun pickZh(index: Int) {
        if (!RimeEngine.ready) return
        zh.track(RimeBridge.preedit())
        RimeBridge.selectCandidateGlobal(rimeIndex(index))
        flushRime()
    }

    // ---- 俄语联想 ----------------------------------------------------------------------------

    /** The word predictor for the language in use, when it has one and it makes sense here. */
    private fun words(): WordPredictor? {
        if (isZh || !prefs.wordPredict || !wordsHere || curLang !in WordPredictor.LANGS) return null
        return wordTables.get(curLang)
    }

    /** Completions of the word being typed, or after a space what may come next. */
    private fun suggestWords() {
        val p = words() ?: return
        if (panel != Panel.LETTERS || sink != null) return
        val before = editor.textBefore(64)
        val (typed, ctx) = WordPredictor.split(before)
        predictions = when {
            typed.isNotEmpty() -> p.complete(typed, ctx)
            // after any space in text (a word, a comma, a sentence end, Chinese before it): what may come next
            before.endsWith(" ") && before.isNotBlank() -> p.following(ctx, capital = WordPredictor.sentenceStart(before))
            else -> emptyList()
        }
        predictionLabel = ""
        // right after a correction the word as it was typed comes first, marked 原: a tap puts it back
        revertChip = corrected?.takeIf { it.isWord && before.endsWith(it.word + it.sep) }?.let { c ->
            predictions = listOf(c.original) + predictions.filter { it != c.original }
            true
        } ?: false
    }
    private var revertChip = false

    /**
     * 自动纠错: the word right before the caret (just finished, a space or punctuation comes next)
     * is corrected when it is an obvious slip. Returns (correction, what was typed), or null.
     */
    private fun autocorrect(): Pair<String, String>? {
        if (!prefs.wordAutocorrect || panel != Panel.LETTERS || sink != null || editor.secret) return null
        val p = words() ?: return null
        val before = editor.textBefore(64)
        val (typed, _) = WordPredictor.split(before)
        if (typed.isEmpty()) return null
        val fix = p.correction(typed, WordPredictor.sentenceStart(before.dropLast(typed.length))) ?: return null
        editor.replaceBefore(typed.length, fix)
        return fix to typed
    }

    /** Backspace right after a correction puts the word back as typed (and it is kept from then on). */
    private fun revertCorrection(): Boolean {
        val c = corrected ?: return false
        corrected = null
        val tail = c.word + c.sep
        if (editor.textBefore(tail.length) != tail) return false
        editor.replaceBefore(tail.length, c.original)
        if (c.isWord) words()?.keep(c.original, learn = learnHere && prefs.wordLearn)
        updateAutoCap(); suggestWords(); syncBar()
        return true
    }

    /** The word right before the caret is finished (a space or punctuation follows): learn it. */
    private fun learnWordBefore() {
        if (isZh || panel != Panel.LETTERS || !wordsHere) return
        val before = editor.textBefore(64)
        val (typed, ctx) = WordPredictor.split(before)
        if (typed.isEmpty()) return
        // 打字数据's word cloud: the letters were counted one by one, the word is counted here
        editor.countWord(typed)
        if (!learnHere || !prefs.wordLearn) return
        val p = words() ?: return
        if (typed.length >= 2) p.learn(ctx, typed, sentenceStart = WordPredictor.sentenceStart(before.dropLast(typed.length)))
    }

    /** A suggestion was tapped: it replaces the letters typed so far, followed by a space. */
    private fun pickWord(i: Int) {
        if (i == 0 && revertChip && corrected != null) {
            // 原: back to the word as typed, the space or punctuation after it kept
            val c = corrected ?: return
            corrected = null; revertChip = false
            val tail = c.word + c.sep
            if (editor.textBefore(tail.length) == tail) editor.replaceBefore(tail.length, c.original + c.sep)
            words()?.keep(c.original, learn = learnHere && prefs.wordLearn)
            suggestWords(); syncBar()
            return
        }
        val word = predictions.getOrNull(i) ?: return
        val p = words()
        val before = editor.textBefore(64)
        val (typed, ctx) = WordPredictor.split(before)
        editor.replaceBefore(typed.length, "$word ")
        editor.countWord(word)
        autoSpaced = true
        if (learnHere && prefs.wordLearn) p?.learn(ctx, word, sentenceStart = WordPredictor.sentenceStart(before.dropLast(typed.length)))
        updateAutoCap()
        predictions = p?.following(ctx + word, capital = false).orEmpty()
        predictionLabel = ""
        syncBar()
    }

    // ---- 声调筛选 ----------------------------------------------------------------------------

    private val toneFilter by lazy { ToneFilter(zh.tones) }
    private val toneFiltering get() = toneFilter.filtering
    private fun rimeIndex(i: Int) = toneFilter.rimeIndex(i)

    private fun onToneFilter(tone: Int) {
        if (!toneFilter.pick(tone)) return
        syncBar()
        if (panel == Panel.CANDIDATES) showPanel(Panel.CANDIDATES)
    }

    /** Up to [n] candidates with their comments, narrowed by the tones picked. */
    private fun zhCandidates(n: Int) = toneFilter.candidates(n, TONE_POOL)

    /** A 云联想 continuation taken: typed after the text, then what may follow it. */
    private fun pickCloud(phrase: String) {
        cloudShown = emptyList()
        val before = editor.textBefore(1)
        val latin = phrase.firstOrNull()?.let { WordPredictor.isWordChar(it) } == true
        // words in Russian or English: a space before when the last word is not yet closed, and after
        val text = when {
            latin && before.isNotEmpty() && WordPredictor.isWordChar(before[0]) -> " $phrase "
            latin -> "$phrase "
            else -> pangu(phrase)
        }
        editor.type(text, pairable = false, source = TypingStats.Source.AI)
        autoSpaced = latin
        predictionLabel = ""
        predictions = if (isZh) zhSuggestions() else { suggestWords(); predictions }
        updateAutoCap()
        syncBar()
    }

    /** AI 候选 taken: it replaces the whole pinyin. */
    private fun commitAiCandidate() {
        val text = aiCand.answerFor(RimeBridge.input()) ?: return
        RimeBridge.clearComposition()
        zh.reset()
        aiCand.clear(); aiShown = false
        editor.type(pangu(text), pairable = false)
        predictions = zhSuggestions()
        predictionLabel = ""
        syncBar()
    }

    /** The candidate of Rime's at the bar's [index] (the AI 候选 shifts the rest by one), or null for the AI 候选 itself. */
    private fun barToRime(index: Int): Int? = when {
        !aiShown || index < AI_SLOT -> index
        index == AI_SLOT -> null
        else -> index - 1
    }

    override fun candidateOptions(index: Int): List<Output> =
        if (index < 0) predictionOptions(-1 - index) else barToRime(index)?.let { zhOptions(it) }.orEmpty()

    override fun onCandidateOption(index: Int, option: Output) {
        if (index < 0) predictionOption(-1 - index, option) else barToRime(index)?.let { zhOption(it, option) }
    }

    /** The word at [i] of the suggestions as the bar shows them: 云联想's continuations first, then the rest without repeats. */
    private fun shownPrediction(i: Int): String? =
        if (cloudShown.isEmpty()) predictions.getOrNull(i)
        else cloudShown.getOrNull(i) ?: predictions.filter { it !in cloudShown }.getOrNull(i - cloudShown.size)

    /** An association held: see [CandidateMenus.prediction]. */
    private fun predictionOptions(i: Int): List<Output> {
        val word = shownPrediction(i) ?: return emptyList()
        return CandidateMenus.prediction(word)
    }

    private fun predictionOption(i: Int, option: Output) {
        val word = shownPrediction(i) ?: return
        when (option.text) {
            "§type" -> onCandidate(-1 - i)
            "§mute" -> {
                // Russian and English words are kept off in lowercase, whatever case they showed in
                if (isZh) { Muted.add(this, word); zhMemory.forget(word) }
                else { Muted.add(this, word.lowercase()); words()?.forget(word) }
                predictions = predictions.filter { it != word }
                cloudShown = cloudShown.filter { it != word }
                syncBar()
                notice("「$word」不会再出现在联想里，可以在「自定义词库」里恢复")
            }
        }
    }

    private fun zhOptions(index: Int): List<Output> {
        val word = RimeBridge.menu(rimeIndex(index) + 1).texts.getOrNull(rimeIndex(index)) ?: return emptyList()
        return CandidateMenus.zh(word)
    }

    private fun zhOption(index: Int, option: Output) {
        if (option.text == "§first" || option.text == "§last") {
            // 雾凇's 以词定字 acts on the highlighted candidate with [ and ]
            // (false when it already is highlighted, as the first one is)
            RimeBridge.highlightCandidate(rimeIndex(index))
            zh.track(RimeBridge.preedit())
            rimeKey(if (option.text == "§first") XK_BRACKETLEFT else XK_BRACKETRIGHT)
            return
        }
        if (option.text == "§mute") {
            val word = RimeBridge.menu(rimeIndex(index) + 1).texts.getOrNull(rimeIndex(index)) ?: return
            Muted.add(this, word)
            restartRimeEngine()
            notice("「$word」不会再出现，可以在「自定义词库」里恢复")
            return
        }
        if (option.text != "§forget") return
        val ok = RimeBridge.deleteCandidate(rimeIndex(index))
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

    /** Candidates fetched for the bar now, for the pinyin in [barFor]. */
    private var barCands = CANDIDATES_ON_BAR
    private var barFor = ""

    override fun onMoreCandidates() {
        if (!composing || barCands >= GRID_CANDIDATES) return
        barCands += CANDIDATES_MORE
        syncBar()
    }

    override fun onExpand() {
        showPanel(if (panel == Panel.CANDIDATES) Panel.LETTERS else Panel.CANDIDATES)
    }

    override fun onClipChip(text: String) {
        clipboard.pasteChip()
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
        query.setLength(0); queryCaret = -1
        query.append(selection ?: initial)
        haptics.play(HapticEvent.FUNCTION)
        showPanel(Panel.LETTERS)
        this.sink = sink
        if (sink == Sink.TRANSLATE) translate.onOpened(selection)
        bar.activeTool = when (sink) { Sink.EMOJI -> "emoji"; Sink.TRANSLATE -> if (translate.mode == "translate") "translate" else "ai" }
        syncBar()
    }

    override fun openStrip(mode: String) {
        translate.nextMode = mode
        openSink(Sink.TRANSLATE)
    }

    /** Closes the query; where it came from opens again (the emoji panel after 查找, the AI panel after 输入指令). */
    override fun closeSink(to: Panel?) {
        val s = sink ?: return
        dropSink()
        showPanel(to ?: when (s) { Sink.EMOJI -> Panel.EMOJI; Sink.TRANSLATE -> Panel.LETTERS })
    }

    /** Forgets the query without changing the panel. */
    private fun dropSink() {
        val s = sink ?: return
        sink = null
        if (composing) RimeBridge.clearComposition()
        zh.reset()
        query.setLength(0); queryCaret = -1
        if (s == Sink.TRANSLATE) translate.onClosed()
    }

    override fun openSettings(page: String?) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (page != null) putExtra("page", page) })
    }

    internal companion object {
        /** The tool panels the back key closes (back to the letters) before it closes the keyboard. */
        val BACK_CLOSES = setOf(Panel.CANDIDATES, Panel.CLIPBOARD, Panel.EMOJI, Panel.AI, Panel.VOICE, Panel.CURSOR, Panel.QUICK)
        val CARET_ACTIONS = setOf("left", "right", "up", "down", "word_left", "word_right", "home", "end")
        /** Candidates fetched for the bar at first: more than fit; the row asks for more as it is scrolled. */
        const val CANDIDATES_ON_BAR = 20
        /** …and how many more each time it does. */
        const val CANDIDATES_MORE = 40
        /** The opened candidate grid. */
        const val GRID_CANDIDATES = 300
        /** Candidates read for 声调筛选, which keeps only some of them. */
        const val TONE_POOL = 400
        /** Where the AI 候选 sits on the bar. */
        const val AI_SLOT = 1
        /** 声调筛选 in the opened candidates. */
        const val XK_BRACKETLEFT = 0x5b
        const val XK_BRACKETRIGHT = 0x5d
    }
}
