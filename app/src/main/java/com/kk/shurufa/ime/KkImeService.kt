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
    private lateinit var toneTabs: com.kk.shurufa.keyboard.TabStrip
    private lateinit var syllableTabs: com.kk.shurufa.keyboard.TabStrip
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
    /** 俄语联想, one per language that has word tables. */
    private val wordPredictors = HashMap<String, WordPredictor>()
    /** Word suggestions make sense in this field (not passwords, addresses, numbers). */
    private var wordsHere = true
    /** What is typed here may be learned from. */
    private var learnHere = true
    /** 云联想, and the continuations at the front of the suggestions now. */
    private val cloud by lazy { CloudPredict(prefs) { syncBar() } }
    private var cloudShown: List<String> = emptyList()
    /** 小K, and its line of the day while it shows. */
    private val kay by lazy { Kay(this, prefs) }
    private var greeting: String? = null
    private var greetingAt = 0L
    /** AI 浮窗, and what it shows now: 云联想's lines or the AI 候选. */
    private lateinit var floats: com.kk.shurufa.keyboard.FloatPills
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
    /** A notice raised before the bar existed (a layout file that failed to load). */
    private var pendingNotice: String? = null

    private val rimeListener: (RimeEngine.State) -> Unit = { onRimeState(it) }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---- lifecycle ----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        com.kk.shurufa.keyboard.FrameRate.window = window.window
        haptics = Haptics(this, prefs)
        repo = LayoutRepo(this, overrides = { KeyOverrides.load(prefs, it) })
        zhMemory = ZhMemory(this).also { if (prefs.zhPredictLearn) it.load() }
        predictor = Predictor(this, zhMemory)
        learning.onTaught = { i -> if (prefs.zhPredictLearn && i.before.isNotEmpty()) zhMemory.learn(i.before, i.text) }
        zh = ZhComposer(this, prefs).also { it.preload() }
        if (prefs.zhPredict) predictor.load()
        if (prefs.packPoetry) predictor.loadPoetry()
        recentSymbols += prefs.state.getString(Prefs.RECENT_SYMBOLS, "")!!.split('\u0001').filter { it.isNotEmpty() }
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
        com.kk.shurufa.keyboard.FrameRate.window = null
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
            // next come the word tables (the Russian ones are about 6 MB): those of languages not
            // in use now are let go, what they learned saved; they load again on first use
            wordPredictors.keys.filter { it != curLang }.forEach { l -> wordPredictors.remove(l)?.save() }
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
    private fun applyPrivacy(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val kind = fieldKind(info)
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
        keyboard = newKeyboard().also { it.touch = if (prefs.touchAdapt) touchModel else null }
        keyboard.learnTaps = tapLearn
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
        floats = com.kk.shurufa.keyboard.FloatPills(this, prefs).also {
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
    private val touchModel by lazy { com.kk.shurufa.keyboard.TouchModel(prefs.state) }

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

    /**
     * "password", "address" (a mail or web address, an account name) or null, by what the field
     * says it is, and, for fields that do not say (login pages often don't), by its hint
     * ("邮箱或手机号", "Email or phone", "Пароль") and by the app (Google's own account screens).
     */
    private fun fieldKind(info: EditorInfo): String? {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return null
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation in PASSWORDS) return "password"
        if (variation in ADDRESSES) return "address"
        if (variation != InputType.TYPE_TEXT_VARIATION_NORMAL && variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) return null
        val words = listOfNotNull(info.hintText, info.label).joinToString(" ").lowercase()
        if (PASSWORD_WORDS.any { it in words }) return "password"
        if (ACCOUNT_WORDS.any { it in words }) return "address"
        if (info.packageName in ACCOUNT_APPS) return "address"
        return null
    }

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
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (prefs.perAppLanguage && !restarting) prefs.appLanguage(info.packageName ?: "")?.takeIf { it in prefs.languages }?.let { lang = it }
        val kind = fieldKind(info)
        val isPassword = kind == "password"
        val isAddress = kind == "address"
        if ((isPassword || isAddress) && (isZh || curLang == "ru")) tempLang = latinLanguage()
        // 打字数据: never from passwords, addresses or fields that ask not to be learned from
        applyPrivacy(info)
        autoSpaced = false
        // the field says what goes in it: numbers get the number pad, with the marks that field takes
        fieldOps = when (cls) {
            InputType.TYPE_CLASS_PHONE -> PHONE_OPS
            InputType.TYPE_CLASS_DATETIME -> DATE_OPS
            InputType.TYPE_CLASS_NUMBER -> buildList {
                if (info.inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0) add("-")
                if (info.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) { add("."); add(",") }
                if (variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD) addAll(listOf("-", "/", ":", " ").filter { it !in this })
            }.ifEmpty { listOf(" ") }
            else -> null
        }
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
            com.kk.shurufa.keyboard.SharkLife.greet(greetingAt)
        }
        if (!prefs.incognito) kay.prefetch()
        // back after a long while: it wakes with a start
        if (!restarting && greeting == null) com.kk.shurufa.keyboard.SharkLife.woke(SystemClock.uptimeMillis())
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
        com.kk.shurufa.keyboard.SharkLife.pat(SystemClock.uptimeMillis())
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
        com.kk.shurufa.keyboard.FrameRate.idle()
        stopBackgroundWork()
        wordPredictors.values.forEach { it.save() }
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
            Panel.LETTERS -> { keyboardPhone = false; keyboard.setRows(lettersRows()); content.addView(keyboard, fixed) }
            Panel.PHONE -> { keyboardPhone = true; keyboard.setRows(layout("phone").rows); content.addView(keyboard, fixed) }
            Panel.MATH -> {
                if (math == null) { showPanel(Panel.LETTERS); return }
                refreshMath(slide = 0)
                content.addView(mathPanel, fixed)
            }
            Panel.NUM -> { numPad.keys.setRows(layout("num").rows); numPad.setItems(numOps.map { if (it == " ") "空格" else it }); content.addView(numPad, fixed); showMoneyChip() }
            Panel.CURSOR -> { editor.refreshSelection(); syncEditPanel(); content.addView(editPanel, fixed) }
            Panel.SYMBOLS -> { refreshSymbols(); content.addView(symbolPanel, fixed) }
            Panel.CLIPBOARD -> { clipboard.refresh(); content.addView(clipPanel, fixed) }
            Panel.QUICK -> { refreshQuick(); content.addView(quickPanel, fixed); if (entering) quickPanel.animateIn() }
            Panel.EMOJI -> { emoji.refresh(); content.addView(emojiPanel, fixed) }
            Panel.AI -> { content.addView(aiPanel, fixed); if (entering) ai.onOpened() }
            Panel.VOICE -> { content.addView(voicePanel, fixed); if (entering) voice.onOpened() }
            Panel.CANDIDATES -> {
                syncToneTabs()
                val (cands, comments) = zhCandidates(300)
                candGrid.notes = zh.annotations(cands, comments)
                candGrid.setItems(cands)
                content.addView(candPanel, fixed)
            }
        }
        if (entering && p != Panel.LETTERS && p != Panel.PHONE && p != Panel.QUICK) animateIn(content.getChildAt(0))
        bar.expanded = p == Panel.CANDIDATES
        bar.activeTool = when (sink) { Sink.EMOJI -> "emoji"; Sink.TRANSLATE -> if (translate.mode == "translate") "translate" else "ai"; Sink.AI -> "ai"; null -> toolFor(p) }
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
        floatCloud = emptyList(); floatCand = null
        syncBarOnly()
        floats.show(floatCand?.let { listOf(it) } ?: floatCloud)
    }

    private fun syncBarOnly() {
        val s = sink
        val letters = panel == Panel.LETTERS
        when {
            panel == Panel.MATH -> bar.showMath(prefs.mathFormat, simple = false)
            panel == Panel.NUM -> bar.showMath(prefs.mathFormat, simple = true)
            // a query the user opened comes first: it works without the Chinese engine
            s == Sink.TRANSLATE && !composing && letters -> { bar.showToolbar(); translate.sync() }
            s == Sink.AI && !composing && letters -> {
                val q = query.toString()
                bar.showQuery("AI 指令", q, "说说想怎么改，比如“改成适合发朋友圈的语气”", if (q.isBlank()) null else "执行")
            }
            greeting != null && s == null && letters && !composing && predictions.isEmpty() -> bar.showGreeting(greeting!!, com.kk.shurufa.online.Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) != null)
            s == Sink.EMOJI && !composing && letters -> {
                val q = query.toString().trim()
                searchResults = emoji.search(q)
                when {
                    q.isEmpty() -> bar.showQuery("查找表情", "", "输入中文或英文关键词，比如 开心、cat", null)
                    else -> bar.showPredictions(searchResults, if (searchResults.isEmpty()) "没有找到和「$q」相关的表情" else "查找「$q」· 点按发送")
                }
            }
            isZh && RimeEngine.state == RimeEngine.State.DEPLOYING && letters -> bar.showMessage("正在部署雾凇拼音词库，首次约需一分钟…", busy = true)
            isZh && RimeEngine.state == RimeEngine.State.PREPARING && letters -> bar.showMessage("正在准备中文词库…", busy = true)
            isZh && RimeEngine.state == RimeEngine.State.FAILED && letters -> bar.showMessage("中文引擎没有启动 · 点按重试", tappable = true)
            composing && pinyinEdit -> bar.showEdit(RimeBridge.input(), RimeBridge.caretPos(), zh.gaps(RimeBridge.input(), RimeBridge.preedit()))
            composing -> {
                val pre = RimeBridge.preedit()
                zh.track(pre)
                val (cands, comments) = zhCandidates(CANDIDATES_ON_BAR)
                val input = RimeBridge.input()
                aiCand.update(input, pre.split(' ', '\'').count { it.isNotBlank() }, cands.firstOrNull().orEmpty(), { editor.textBefore(20) },
                    allowed = learnHere && sink == null && !toneFiltering)
                val aiAnswer = aiCand.answerFor(input)
                // in the 浮窗 it leaves the bar as Rime has it
                val floating = prefs.aiPlace == "float"
                if (floating) floatCand = aiAnswer
                val ai = aiAnswer.takeIf { !floating }
                aiShown = ai != null
                val notes = zh.annotations(cands, comments)
                // second, marked AI: the first stays Rime's, which is what space takes
                if (ai != null) bar.showCandidates(pre, cands.take(1) + ai + cands.drop(1),
                    notes.take(1).ifEmpty { listOf("") } + "AI" + notes.drop(1), zh.caretInPreedit(pre, RimeBridge.cursor()))
                else bar.showCandidates(pre, cands, notes, zh.caretInPreedit(pre, RimeBridge.cursor()))
                if (prefs.zhPreeditInline) editor.compose(RimeBridge.input())
            }
            predictions.isNotEmpty() && letters -> {
                // 云联想: the AI's continuations of this text, when they came, go first, marked 云
                val before = editor.textBefore(prefs.cloudContext)
                val revert = revertChip && corrected != null
                if (predictionLabel.isEmpty() && !revert) cloud.update(before, allowed = learnHere && sink == null)
                if (com.kk.shurufa.online.AiBudget.justPaused) {
                    com.kk.shurufa.online.AiBudget.justPaused = false
                    notice("AI 服务说请求太多（免费额度可能用完了），云联想先歇 30 分钟")
                }
                cloudShown = if (predictionLabel.isEmpty() && !revert) cloud.answerFor(before) else emptyList()
                if (prefs.aiPlace == "float" && cloudShown.isNotEmpty()) { floatCloud = cloudShown; cloudShown = emptyList() }
                if (revert) bar.showPredictions(predictions, predictionLabel, listOf("原"))
                else if (cloudShown.isEmpty()) bar.showPredictions(predictions, predictionLabel)
                else bar.showPredictions(cloudShown + predictions.filter { it !in cloudShown }, predictionLabel, cloudShown.map { "云" })
            }
            clipboard.pendingChip() != null && letters && !prefs.incognito -> clipboard.chip()?.let { (text, kind) -> bar.showClip(text, kind) }
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
            onItem = { i -> pickZh(i) }
            onFeedback = { k -> haptics.play(if (k == "popup") HapticEvent.POPUP else HapticEvent.LETTER) }
            longOptions = { i -> zhOptions(i) }
            onLongOption = { i, o -> zhOption(i, o) }
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
        // 声调筛选, in the opened candidates only: the bar stays as it was
        toneTabs = com.kk.shurufa.keyboard.TabStrip(this, prefs).apply {
            theme = kbTheme; compact = true
            onFeedback = { haptics.play(HapticEvent.OPTION) }
            onTab = { t -> onToneFilter(t.toInt()) }
            onVerticalDrag = { phase, y, v -> when (phase) { 0 -> candHandle.begin(y); 1 -> candHandle.drag(y); else -> candHandle.end(v) } }
        }
        candPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candPanel.addView(candHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // with several syllables, a row above picks which one the tones are for
        syllableTabs = com.kk.shurufa.keyboard.TabStrip(this, prefs).apply {
            theme = kbTheme; compact = true
            onFeedback = { haptics.play(HapticEvent.OPTION) }
            onTab = { t -> toneSyllable = t.toInt(); syncToneTabs() }
            onVerticalDrag = { phase, y, v -> when (phase) { 0 -> candHandle.begin(y); 1 -> candHandle.drag(y); else -> candHandle.end(v) } }
        }
        candPanel.addView(syllableTabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        candPanel.addView(toneTabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        if (!prefs.incognito) prefs.state.edit().putString(Prefs.RECENT_SYMBOLS, recentSymbols.joinToString("\u0001")).apply()
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
            "up" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> editor.caretStep(KeyEvent.KEYCODE_DPAD_RIGHT)
            "home" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_HOME, editor.shiftMeta())
            "end" -> editor.sendKey(KeyEvent.KEYCODE_MOVE_END, editor.shiftMeta())
            "select_all" -> { editor.contextMenu(android.R.id.selectAll); editor.selectMode = true }
            "backspace" -> { haptics.play(HapticEvent.REPEAT); editor.deleteBackward() }
            "copy" -> { copied(editor.copySelection(cut = false), "已复制"); editor.selectMode = false; editor.collapseSelection() }
            "cut" -> { copied(editor.copySelection(cut = true), "已剪切"); editor.selectMode = false }
            "paste" -> { editor.contextMenu(android.R.id.paste); editor.selectMode = false }
        }
        syncEditPanel()
    }

    /** 简易/科学: !#1 and number fields open the number keyboard in the mode used last. */
    private fun numPanel() = if (prefs.numMode == "sci") Panel.MATH else Panel.NUM

    /** The marks of the number pad's column for the field: a phone number's, a date's, a number's; null in a text field (the arithmetic ones). */
    private var fieldOps: List<String>? = null
    private val numOps get() = fieldOps ?: NUM_OPS

    private fun showCalcResult() {
        if (!prefs.mathCalc) return
        val line = editor.textBefore(200).substringAfterLast('\n').dropLast(1)
        Calc.eval(line.substringAfterLast('='))?.let { bar.showResult(Calc.format(it)) }
    }

    private fun buildNumPad() {
        val strip = GridPanel(this, prefs).apply {
            theme = kbTheme; cellStyle = GridPanel.CellStyle.SOFT; columns = 1; textSp = 20f
            cellPadDp = 0f; cellGapDp = prefs.keyGapDp
            onItem = { i -> numOps.getOrNull(i)?.let { onNumOp(it) } }
            onFeedback = { haptics.play(HapticEvent.LETTER) }
        }
        numPad = NumPadView(this, newKeyboard(), strip)
    }

    private fun onNumOp(op: String) {
        finishComposing()
        editor.type(op, pairable = op == "(")
        if (op == "=") showCalcResult() else bar.showResult(null)
        showMoneyChip()
    }

    /** 大写金额: after a number on the number pad, a chip offers it written out (壹佰贰拾叁元整). */
    private fun showMoneyChip() {
        // a phone number, a card's digits, a code: no 大写金额 for those
        if (fieldOps != null) { bar.showMoney(null); return }
        val num = Regex("(\\d{1,16}(?:\\.\\d{1,2})?)$").find(editor.textBefore(24))?.value
        val before = editor.textBefore(26).dropLast(num?.length ?: 0)
        // not the digits of a longer expression's result or a date
        bar.showMoney(if (num != null && !before.endsWith("=") && !before.endsWith(".")) ZhNumber.money(num)?.let { num to it } else null)
    }

    override fun onMoneyChip(number: String, words: String) {
        if (editor.textBefore(number.length) == number) editor.replaceBefore(number.length, words)
        bar.showMoney(null)
    }

    /** 日期 · 时间 · 农历: types 雾凇's trigger, whose candidates then fill the bar. */
    private fun rimeTool(code: String) {
        if (!RimeEngine.ready) { notice("中文引擎还没准备好"); return }
        if (!isZh) setLanguage(prefs.languages.firstOrNull { layout(it).engine == "rime" } ?: "zh")
        finishComposing()
        showPanel(Panel.LETTERS)
        RimeBridge.setInput(code)
        flushRime()
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
        // switches for the language in use: 中文's own three, or 俄语重音
        val langSwitches = when {
            isZh -> listOf(
                QuickPanel.Tile("trad", "繁体", on = prefs.zhTraditional),
                QuickPanel.Tile("emoji", "候选表情", on = prefs.zhEmoji),
                QuickPanel.Tile("pinyin", "拼音模式", on = prefs.pinyinMode))
            curLang == "ru" -> listOf(QuickPanel.Tile("stress", "俄语重音", on = prefs.stressMode))
            else -> emptyList()
        }
        quickPanel.sections = listOf(
            QuickPanel.Section(QuickPanel.Kind.SWITCHES, "", listOf(
                QuickPanel.Tile("incognito", "临时模式", on = prefs.incognito),
                QuickPanel.Tile("predict", "联想", on = if (isZh) prefs.zhPredict else prefs.wordPredict),
            ) + (if (isZh) listOf(QuickPanel.Tile("correct", "纠错", on = prefs.zhCorrection)) else emptyList()) + langSwitches + listOf(
                QuickPanel.Tile("vibrate", "振动", on = prefs.vibrate),
                QuickPanel.Tile("sound", "按键音", on = prefs.sound),
            )),
            QuickPanel.Section(QuickPanel.Kind.ROW, "插入", listOf(
                QuickPanel.Tile("date", "日期"), QuickPanel.Tile("time", "时间"), QuickPanel.Tile("week", "星期"),
                QuickPanel.Tile("lunar", "农历"), QuickPanel.Tile("calc", "计算 · 大写"))),
            QuickPanel.Section(QuickPanel.Kind.ROW, "工具", tools),
            QuickPanel.Section(QuickPanel.Kind.FOOTER, "", listOf(
                QuickPanel.Tile("theme", "主题 · $themeName"),
                QuickPanel.Tile("toolbar", "工具栏"),
                QuickPanel.Tile("picker", "切换输入法"),
                QuickPanel.Tile("settings", "设置"))),
        )
    }

    private fun onQuickTile(id: String) {
        when (id) {
            in RIME_TOOLS -> rimeTool(RIME_TOOLS[id]!!)
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
            // learned later if it stays (a slip deleted right away teaches nothing)
            if (learnHere && commit.any { isCjk(it) }) learning.committed(syl.joinToString(""), commit, before = editor.textBefore(8))
            restoreFullWidth(out)
            val spaced = pangu(out)
            editor.type(spaced, pairable = false, syllables = if (out == commit) syl else null)
            corrected = null; madeAuto(spaced, out)
            predictions = if (prefs.zhPredict && !prefs.pinyinMode && out.any { isCjk(it) })
                predictor.suggest(editor.textBefore(20).ifEmpty { out }) else emptyList()
            predictionLabel = ""
        } else if (prefs.zhPreeditInline && RimeBridge.input().isEmpty()) editor.finishComposingText()
        if (RimeBridge.input().isEmpty()) { zh.reset(); pinyinEdit = false; aiCand.clear(); aiShown = false }
        syncBar()
        if (panel == Panel.CANDIDATES) {
            if (composing) {
                val (c, comments) = zhCandidates(300)
                candGrid.notes = zh.annotations(c, comments)
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
        autoSpaced = false
        if (!isZh) { suggestWords(); return }
        if (panel != Panel.LETTERS) return
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
            showMoneyChip()
            return
        }
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
            val half = smartPunct(t)
            val made = pangu(half)
            editor.type(made)
            // a mark made half-width is not put back by backspace: deleted and typed again soon, it is the full one
            if (half == t) madeAuto(made, t)
            // 3, stays half-width before more digits; Chinese after it turns it back (3，好的)
            halfAfterDigit = if (half != t) half to t else null
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

    /**
     * 盘古之白: a space between Chinese and Latin letters or digits ("用 iPhone 拍了 3 张"), put in
     * front of [t] when the character before the caret and [t]'s first one are of the two kinds.
     * Off by default; never in 拼音模式, which spaces its own syllables.
     */
    private fun pangu(t: String): String {
        if (!prefs.pangu || prefs.pinyinMode || t.isEmpty() || !wordsHere) return t
        val prev = editor.textBefore(1).firstOrNull() ?: return t
        val first = t[0]
        fun latin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        return if ((isCjk(prev) && latin(first)) || (latin(prev) && isCjk(first))) " $t" else t
    }

    /**
     * 双击句号: the period tapped again right away becomes an ellipsis, 。。→ …… and .. → …
     * Returns true when it did (and the tap is used up).
     */
    private fun ellipsis(t: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val quick = now - lastPeriodAt < DOUBLE_PERIOD_MS
        lastPeriodAt = if (t == "。" || t == ".") now else 0L
        if (!prefs.doublePeriodEllipsis || !quick || (t != "。" && t != ".")) return false
        if (editor.textBefore(1) != t) return false
        lastPeriodAt = 0L
        val made = if (t == "。") "……" else "…"
        editor.replaceBefore(1, made)
        corrected = Correction(made, t + t, "", isWord = false)
        return true
    }
    private var lastPeriodAt = 0L

    /** A mark made half-width after a digit (",", "，"), until something else is typed after it. */
    private var halfAfterDigit: Pair<String, String>? = null

    /** Chinese going in right after such a mark: it was a sentence's after all, so it is full-width again. */
    private fun restoreFullWidth(next: String) {
        val (half, full) = halfAfterDigit ?: return
        halfAfterDigit = null
        if (next.firstOrNull()?.let { isCjk(it) } != true) return
        val before = editor.textBefore(2)
        if (before.length == 2 && before[0].isDigit() && before.substring(1) == half) editor.replaceBefore(1, full)
    }

    /** The full-width mark whose half-width form was just deleted, and when: typed again soon, it stays full. */
    private var halfUndone: String? = null
    private var halfUndoneAt = 0L

    /**
     * 数字后的标点保持半角: after a digit, Chinese punctuation that would break a number is typed
     * half-width. Deleted and typed again within a few seconds, it is the Chinese mark after all
     * (once: the next time after a digit it is half-width again).
     */
    private fun smartPunct(t: String): String {
        if (!prefs.smartPunct) return t
        val undone = halfUndone
        halfUndone = null
        if (undone == t && SystemClock.uptimeMillis() - halfUndoneAt < HALF_RETYPE_MS) return t
        val before = editor.textBefore(1)
        if (before.isNotEmpty() && before[0].isDigit()) return when (t) { "。" -> "."; "：" -> ":"; "，" -> ","; else -> t }
        return t
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
                    sink != null -> { if (query.isNotEmpty()) query.setLength(query.offsetByCodePoints(query.length, -1)); syncBar() }
                    revertCorrection() -> {}
                    else -> {
                        halfAfterDigit?.let { (half, full) -> if (editor.textBefore(half.length) == half) { halfUndone = full; halfUndoneAt = SystemClock.uptimeMillis() } }
                        learning.deleting { n -> editor.textBefore(n) }
                        editor.deleteBackward(); updateAutoCap(); suggestAfterDelete(); if (panel == Panel.NUM) showMoneyChip(); syncBar()
                    }
                }
            }
            "space" -> {
                if (composing) { rimeKey(RimeBridge.XK_space); return }
                if (sink != null) { query.append(' '); syncBar(); return }
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
                sink == Sink.AI -> runInstruction()
                sink == Sink.TRANSLATE -> translate.enter()
                sink == Sink.EMOJI -> searchResults.firstOrNull()?.let { emoji.type(it) }
                else -> {
                    // 发送: 小K jumps for the message that went out
                    val info = currentInputEditorInfo
                    if (info != null && (info.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND &&
                        (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) { com.kk.shurufa.keyboard.SharkLife.sent(SystemClock.uptimeMillis()); keyboard.invalidate(); bar.invalidate() }
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
                val recent = lastSymbolsAt > 0 && SystemClock.uptimeMillis() - lastSymbolsAt < prefs.symbolMemorySec * 1000L
                showPanel(if (recent) Panel.SYMBOLS else numPanel())
            }
            "sci" -> { prefs.put(Prefs.NUM_MODE, "sci"); showPanel(Panel.MATH) }
            "simple" -> { prefs.put(Prefs.NUM_MODE, "simple"); showPanel(Panel.NUM) }
            "num" -> showPanel(numPanel())
            "more_symbols" -> showPanel(Panel.SYMBOLS)
            "math" -> { finishComposing(); showPanel(Panel.MATH) }
            "abc" -> { editor.selectMode = false; showPanel(Panel.LETTERS) }
            "collapse" -> { tones = IntArray(0); showPanel(Panel.LETTERS) }
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
            "copy" -> { copied(editor.copySelection(cut = false), "已复制"); editor.selectMode = false }
            "cut" -> { copied(editor.copySelection(cut = true), "已剪切"); editor.selectMode = false }
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
        if (key.fn == "backspace") com.kk.shurufa.keyboard.SharkLife.delete(now) else com.kk.shurufa.keyboard.SharkLife.key(now)
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
            "translate" -> if (sink == Sink.TRANSLATE && translate.mode == "translate") closeSink() else { translate.nextMode = "translate"; openSink(Sink.TRANSLATE) }
            else -> if (target != null) { if (sink != null) dropSink(); showPanel(target) }
        }
    }

    override fun onCandidate(index: Int) {
        // what was read of the field lasts for one key at most (the app may have changed it since)
        editor.dirty()
        if (index < 0 && sink == Sink.AI && !composing) { runInstruction(); return }
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
            predictions = predictor.suggest(editor.textBefore(20).ifEmpty { word })
            predictionLabel = ""
            syncBar()
            return
        }
        if (aiShown) {
            // the bar's second word is the AI's reading of the whole pinyin
            if (index == AI_SLOT) { commitAiCandidate(); return }
            pickZh(if (index > AI_SLOT) index - 1 else index); return
        }
        pickZh(index)
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
        return wordPredictors.getOrPut(curLang) { WordPredictor(this, curLang) }.also { it.load() }
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
        if (!learnHere || !prefs.wordLearn) return
        val p = words() ?: return
        val before = editor.textBefore(64)
        val (typed, ctx) = WordPredictor.split(before)
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
        autoSpaced = true
        if (learnHere && prefs.wordLearn) p?.learn(ctx, word, sentenceStart = WordPredictor.sentenceStart(before.dropLast(typed.length)))
        updateAutoCap()
        predictions = p?.following(ctx + word, capital = false).orEmpty()
        predictionLabel = ""
        syncBar()
    }

    // ---- 声调筛选 ----------------------------------------------------------------------------

    /**
     * 声调筛选, syllable by syllable: [tones] holds the tone kept for each syllable typed (1–4, 0
     * for any), for the pinyin in [toneFor] only. [toneSyllable] is the one the tone row is for.
     */
    private var tones = IntArray(0)
    private var toneFor = ""
    private var toneSyllable = 0
    private val toneFiltering get() = tones.any { it != 0 }
    /** While filtering: position on the bar or in the grid → Rime's own candidate index. */
    private var toneIdx: IntArray? = null

    private fun rimeIndex(i: Int) = toneIdx?.getOrNull(i) ?: i

    /** The syllables still to be chosen for (what Rime shows after any part already picked). */
    private fun toneSyllables(): List<String> =
        RimeBridge.preedit().split(' ', '\'').filter { s -> s.isNotEmpty() && s.all { it in 'a'..'z' } }

    private fun onToneFilter(tone: Int) {
        val syl = toneSyllables()
        if (syl.isEmpty()) return
        if (RimeBridge.input() != toneFor || tones.size != syl.size) { tones = IntArray(syl.size); toneFor = RimeBridge.input() }
        val at = toneSyllable.coerceIn(0, syl.size - 1)
        tones[at] = tone
        // on to the next syllable that has no tone yet, so 你好 is two taps
        if (tone != 0) (1 until syl.size).map { (at + it) % syl.size }.firstOrNull { tones[it] == 0 }?.let { toneSyllable = it }
        syncBar()
        if (panel == Panel.CANDIDATES) showPanel(Panel.CANDIDATES)
    }

    /**
     * The tone rows of the opened candidates: the syllables (each with the tone picked for it,
     * nǐ·hao), and the four tones of the chosen one written on it (nī ní nǐ nì), so it is plain
     * which character is being narrowed.
     */
    private fun syncToneTabs() {
        val syl = toneSyllables()
        val on = prefs.toneFilter && syl.isNotEmpty()
        if (RimeBridge.input() != toneFor || tones.size != syl.size) { tones = IntArray(syl.size); toneFor = RimeBridge.input(); toneSyllable = 0 }
        toneSyllable = toneSyllable.coerceIn(0, (syl.size - 1).coerceAtLeast(0))
        syllableTabs.visibility = if (on && syl.size > 1) View.VISIBLE else View.GONE
        toneTabs.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        if (syl.size > 1) syllableTabs.setTabs(syl.mapIndexed { i, s -> i.toString() to PinyinTones.mark(s, tones[i]) }, toneSyllable.toString())
        val s = syl[toneSyllable]
        val vowel = s.any { it in "aeiouv" }
        toneTabs.setTabs(listOf("0" to "全部") + (1..4).map { t -> t.toString() to (if (vowel) PinyinTones.mark(s, t) else "ˉˊˇˋ"[t - 1].toString()) },
            tones[toneSyllable].toString())
    }

    /**
     * Up to [n] candidates with their comments. With tones picked, only those whose characters
     * have them, each for its own syllable (行 is xíng for "xing", háng for "hang"); a candidate
     * shorter than the syllables is checked as far as it goes.
     */
    private fun zhCandidates(n: Int): Pair<List<String>, List<String>> {
        if (toneFiltering && RimeBridge.input() != toneFor) tones = IntArray(0)
        if (!toneFiltering) {
            toneIdx = null
            val c = RimeBridge.candidates(n).toList()
            return c to RimeBridge.candidateComments(c.size).toList()
        }
        val all = RimeBridge.candidates(400).toList()
        val comments = RimeBridge.candidateComments(all.size).toList()
        val syl = toneSyllables()
        val keep = all.indices.filter { i ->
            val c = all[i]
            tones.indices.all { k -> tones[k] == 0 || (k >= c.length && k > 0) || c.getOrNull(k)?.let { ch -> tones[k] in zh.tones.tonesOf(ch, syl.getOrNull(k)) } == true }
        }
        toneIdx = keep.toIntArray()
        val shown = keep.take(n)
        return shown.map { all[it] } to shown.map { comments.getOrElse(it) { "" } }
    }

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
        predictions = if (isZh) { if (prefs.zhPredict && !prefs.pinyinMode) predictor.suggest(editor.textBefore(20)) else emptyList() }
            else { suggestWords(); predictions }
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
        predictions = if (prefs.zhPredict && !prefs.pinyinMode) predictor.suggest(editor.textBefore(20)) else emptyList()
        predictionLabel = ""
        syncBar()
    }

    override fun candidateOptions(index: Int): List<Output> = when {
        index < 0 -> predictionOptions(-1 - index)
        !aiShown -> zhOptions(index)
        index == AI_SLOT -> emptyList()
        else -> zhOptions(if (index > AI_SLOT) index - 1 else index)
    }

    override fun onCandidateOption(index: Int, option: Output) = when {
        index < 0 -> predictionOption(-1 - index, option)
        !aiShown -> zhOption(index, option)
        index == AI_SLOT -> Unit
        else -> zhOption(if (index > AI_SLOT) index - 1 else index, option)
    }

    /** An association held: put it in as it is, or never offer it again (and forget it was learned). */
    private fun predictionOptions(i: Int): List<Output> {
        val word = predictions.getOrNull(i) ?: return emptyList()
        val shown = if (word.codePointCount(0, word.length) > 6) word.substring(0, word.offsetByCodePoints(0, 5)) + "…" else word
        return listOf(Output("取消", "§cancel"), Output("上屏「$shown」", "§type"), Output("不再联想「$shown」", "§mute"))
    }

    private fun predictionOption(i: Int, option: Output) {
        val word = predictions.getOrNull(i) ?: return
        when (option.text) {
            "§type" -> onCandidate(-1 - i)
            "§mute" -> {
                Muted.add(this, word)
                zhMemory.forget(word)
                predictions = predictions.filter { it != word }
                syncBar()
                notice("「$word」不会再出现在联想里，可以在「自定义词库」里恢复")
            }
        }
    }

    private fun zhOptions(index: Int): List<Output> {
        val word = RimeBridge.candidates(rimeIndex(index) + 1).getOrNull(rimeIndex(index)) ?: return emptyList()
        val shown = if (word.codePointCount(0, word.length) > 6) word.substring(0, word.offsetByCodePoints(0, 5)) + "…" else word
        val chars = word.codePoints().toArray().filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        // 以词定字: a character of a word, the rest of the pinyin stays
        val pick = if (word.codePointCount(0, word.length) >= 2 && chars.size >= 2) listOf(
            Output("取首字「${String(Character.toChars(chars.first()))}」", "§first"),
            Output("取末字「${String(Character.toChars(chars.last()))}」", "§last")) else emptyList()
        return listOf(Output("取消", "§cancel")) + pick + Output("不再优先「$shown」", "§forget") + Output("不再出现「$shown」", "§mute")
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
            val word = RimeBridge.candidates(rimeIndex(index) + 1).getOrNull(rimeIndex(index)) ?: return
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

    override fun onExpand() {
        showPanel(if (panel == Panel.CANDIDATES) Panel.LETTERS else Panel.CANDIDATES)
    }

    override fun onClipChip(text: String) {
        clipboard.pasteChip()
        syncBar()
    }

    override fun onQueryAction() { if (sink == Sink.AI) runInstruction() }

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
        if (sink == Sink.TRANSLATE) translate.onOpened(selection)
        bar.activeTool = when (sink) { Sink.EMOJI -> "emoji"; Sink.TRANSLATE -> if (translate.mode == "translate") "translate" else "ai"; Sink.AI -> "ai" }
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
        // instructions are typed in 小K's strip now
        if (instruction.isNotBlank()) { translate.nextMode = "rewrite"; openSink(Sink.TRANSLATE) } else showPanel(Panel.AI)
    }

    override fun openSettings(page: String?) {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (page != null) putExtra("page", page) })
    }

    internal companion object {
        /** 3。 deleted and typed again within this long: the full-width mark stays. */
        const val HALF_RETYPE_MS = 4000L
        /** 运算符列 of the standard number pad: five on screen, the rest by scrolling. */
        val NUM_OPS = listOf("+", "−", "×", "÷", "=", "%", "(", ")", "/", ",", "±")
        /** 电话 fields: what goes in a phone number (and the * and # of a dial pad). */
        val PHONE_OPS = listOf("+", "-", " ", "*", "#", "(", ")", ",", ";")
        val DATE_OPS = listOf("-", "/", ":", " ", ".")
        val DIRS = listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT)
        const val VOWELS = "аеёиоуыэюя"
        /** Candidates fetched for the bar: more than fit, so a swipe finds more without a pause. */
        const val CANDIDATES_ON_BAR = 60
        /** Where the AI 候选 sits on the bar. */
        const val AI_SLOT = 1
        /** Two taps of the period within this make an ellipsis. */
        const val DOUBLE_PERIOD_MS = 400L
        /** 声调筛选 in the opened candidates. */
        const val XK_BRACKETLEFT = 0x5b
        const val XK_BRACKETRIGHT = 0x5d
        /** Quick-panel tools that open 雾凇's own candidates: tile → what is typed for them. */
        val RIME_TOOLS = linkedMapOf("date" to "rq", "time" to "sj", "week" to "xq", "lunar" to "nl")
        val PASSWORDS = setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val PASSWORD_WORDS = listOf("密码", "password", "passcode", "пароль", "口令")
        val ACCOUNT_WORDS = listOf("邮箱", "电子邮件", "email", "e-mail", "账号", "帐号", "用户名", "username", "user name", "login", "登录名",
            "почта", "логин", "网址", "url", "域名")
        /** Apps whose text fields are account names and the like (Google's sign-in). */
        val ACCOUNT_APPS = setOf("com.google.android.gms", "com.google.android.gsf.login")
        val ADDRESSES = setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    }
}
