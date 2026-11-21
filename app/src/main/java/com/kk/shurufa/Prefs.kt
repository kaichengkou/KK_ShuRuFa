package com.kk.shurufa

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user setting in one place. The settings screen and the input method both read from
 * here. What the keyboard merely remembers while it is used (recent emoji and symbols, the
 * language of each app, hints already seen) lives apart in [state], so remembering something
 * never looks like a change of settings.
 */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("kk_prefs", Context.MODE_PRIVATE)
    /** The keyboard's own memory: never shown as settings, never rebuilds anything. */
    val state: SharedPreferences = context.applicationContext.getSharedPreferences("kk_state", Context.MODE_PRIVATE)

    init { migrateState() }

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun f(key: String, def: Float) = sp.getFloat(key, def)
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def

    // Appearance
    val theme get() = s(THEME, "system")                 // system | light | dark
    /** 风格: modern (cool grey, sans) or paper (纸墨: rice paper and ink, 宋体 candidates). */
    val style get() = s(STYLE, "modern")
    val keyHeightDp get() = i(KEY_HEIGHT, 48)
    /** Between keys of a row. */
    val keyGapDp get() = f(KEY_GAP, 5f)
    /** Between rows: wider than between keys, so each row reads as one band. */
    val rowGapDp get() = f(ROW_GAP, 8f)
    val keyRadiusDp get() = f(KEY_RADIUS, 8f)
    val labelScale get() = f(LABEL_SCALE, 1f)
    val hintScale get() = f(HINT_SCALE, 1f)
    val showHints get() = hintMode != "none"
    /** 角标: all (every key's slide characters), digits (the top row's numbers only), none. */
    val hintMode get() = s(HINT_MODE, if (b(SHOW_HINTS, true)) "all" else "none")
    /** 候选字体: sans (黑体) or serif (宋体); the paper style defaults to 宋体. */
    val candFont get() = s(CAND_FONT, if (style == "paper") "serif" else "sans")
    /** 节气: the space key names the solar term on its day. */
    val solarTerms get() = b(SOLAR_TERMS, true)
    val showAllHints get() = b(SHOW_ALL_HINTS, false)
    val keyBorders get() = b(KEY_BORDERS, true)
    val numberRow get() = b(NUMBER_ROW, false)
    val candidateScale get() = f(CAND_SCALE, 1f)
    val bottomPaddingDp get() = i(BOTTOM_PADDING, 4)
    val accent get() = s(ACCENT, "teal")                  // teal | indigo | coral | green | violet | graphite | system
    val cursorLabels get() = s(CURSOR_LABELS, "icon")    // icon | both | text

    // Motion
    val animations get() = b(ANIMATIONS, true)
    val animSpeed get() = f(ANIM_SPEED, 1f)               // duration multiplier, lower is faster
    val keyPreview get() = b(KEY_PREVIEW, true)

    // Feedback
    val vibrate get() = b(VIBRATE, true)
    val sound get() = b(SOUND, false)
    val soundVolume get() = f(SOUND_VOLUME, 0.3f)

    // Gestures
    val flickDp get() = f(FLICK_DP, 22f)
    val longPressMs get() = i(LONG_PRESS_MS, 300)
    val spaceSwipe get() = s(SPACE_SWIPE, "language")     // language | cursor | none
    val spaceLongPress get() = s(SPACE_LONG_PRESS, "cursor") // cursor | none
    val cursorStepDp get() = f(CURSOR_STEP_DP, 11f)
    val backspaceSwipe get() = b(BACKSPACE_SWIPE, true)   // up = clear, down = undo
    val backspaceRepeatMs get() = i(BACKSPACE_REPEAT, 55)
    val lpCase get() = b(LP_CASE, true)
    val lpHints get() = b(LP_HINTS, true)
    val lpDigits get() = b(LP_DIGITS, true)
    val topRowScripts get() = b(TOP_ROW_SCRIPTS, false)
    /** 误触: a key dragged far away does nothing. */
    val dragCancel get() = b(DRAG_CANCEL, true)
    /** 误触: a tap held longer than this does nothing (0 = off). */
    val holdCancelMs get() = i(HOLD_CANCEL_MS, 1000)

    // Languages
    val languages: List<String> get() = s(LANGUAGES, "zh,ru,en").split(",").filter { it.isNotBlank() }
    val autoCap get() = b(AUTO_CAP, true)
    val doubleSpacePeriod get() = b(DOUBLE_SPACE, true)
    val perAppLanguage get() = b(PER_APP_LANG, true)
    val stressMode get() = b(STRESS_MODE, false)

    // Chinese
    val zhTraditional get() = b(ZH_TRAD, false)
    val zhEmoji get() = b(ZH_EMOJI, true)
    val zhPreeditInline get() = b(ZH_PREEDIT_INLINE, false)
    val zhPredict get() = b(ZH_PREDICT, true)
    val zhCorrection get() = b(ZH_CORRECTION, true)
    val pinyinMode get() = b(PINYIN_MODE, false)
    val backspaceReselect get() = b(BACKSPACE_RESELECT, true)
    val typoMark get() = b(TYPO_MARK, true)
    val rareAnnotate get() = b(RARE_ANNOTATE, true)
    val packPoetry get() = b(PACK_POETRY, true)
    /** 语法模型: used when downloaded, unless turned off. */
    val grammar get() = b(GRAMMAR, true)
    /** 落点自适应: learn where taps land on each letter key. */
    val touchAdapt get() = b(TOUCH_ADAPT, true)
    val dictFeeds get() = s(DICT_FEEDS, "")
    /** 打字数据: count what is written, on this phone only. */
    val typingStats get() = b(TYPING_STATS, true)

    // Clipboard
    val clipKeep get() = i(CLIP_KEEP, 40)
    val clipSuggest get() = b(CLIP_SUGGEST, true)
    val clipMaxChars get() = i(CLIP_MAX_CHARS, 80)
    val clipFriction get() = f(CLIP_FRICTION, 0.8f)
    val clipColumns get() = i(CLIP_COLUMNS, 2)

    // Symbols and numbers
    val autoPair get() = b(AUTO_PAIR, true)
    val symbolAutoReturn get() = b(SYMBOL_RETURN, true)
    val smartPunct get() = b(SMART_PUNCT, true)
    val symbolMemorySec get() = i(SYMBOL_MEMORY, 7)
    val numMode get() = s(NUM_MODE, "simple")            // simple | sci
    val mathFormat get() = s(MATH_FORMAT, "uni")          // uni | tex
    val mathCalc get() = b(MATH_CALC, true)
    val numberFieldMath get() = b(NUMBER_FIELD_MATH, true)
    val emojiAutoUpdate get() = b(EMOJI_AUTO_UPDATE, true)

    // Voice
    val voiceEngine get() = s(VOICE_ENGINE, "whisper")
    val voiceModel get() = s(VOICE_MODEL, "small")
    val voiceSource get() = s(VOICE_SOURCE, "hf")
    val voiceLang get() = s(VOICE_LANG, "follow")
    val voiceZhModel get() = s(VOICE_ZH_MODEL, "sensevoice")
    val voiceEnModel get() = s(VOICE_EN_MODEL, "sensevoice")
    val voicePunct get() = s(VOICE_PUNCT, "full")
    val voicePangu get() = b(VOICE_PANGU, false)
    val voiceFillers get() = b(VOICE_FILLERS, true)
    val voicePauseMs get() = i(VOICE_PAUSE_MS, 700)
    val voiceCommitPauseMs get() = i(VOICE_COMMIT_PAUSE, 2000)
    val voiceAutoStopSec get() = i(VOICE_AUTO_STOP, 3)
    val voiceReturn get() = b(VOICE_RETURN, false)

    // Online
    /** The Claude key from before several services could be set up; read as Claude's key. */
    val aiKey get() = s(AI_KEY, "")
    val aiProvider get() = s(AI_PROVIDER, "claude")
    val translateTarget get() = s(TRANSLATE_TARGET, "en")
    val translateWifiOnly get() = b(TRANSLATE_WIFI, false)
    /** "auto", "mlkit" or an AI target ("id|model", see online.Llm). */
    val translateEngine get() = s(TRANSLATE_ENGINE, "auto")
    /** 工具栏: the buttons in order (快捷面板 is always added last). */
    val toolbar: List<String> get() = s(TOOLBAR_ITEMS, "ai,emoji,mic,translate,cursor,clipboard").split(',').filter { it.isNotBlank() }.take(6)

    fun put(key: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Float -> e.putFloat(key, v)
            is String -> e.putString(key, v)
        }
        e.apply()
    }

    fun appLanguage(pkg: String): String? = state.getString("app_lang_$pkg", null)
    fun setAppLanguage(pkg: String, lang: String) = state.edit().putString("app_lang_$pkg", lang).apply()

    /** Older versions kept the keyboard's memory among the settings: move it over once. */
    private fun migrateState() {
        if (state.getBoolean(MIGRATED, false)) return
        val e = state.edit()
        val s = sp.edit()
        for ((k, v) in sp.all) {
            if (k in STATE_KEYS || STATE_PREFIXES.any { k.startsWith(it) }) {
                when (v) {
                    is String -> e.putString(k, v)
                    is Long -> e.putLong(k, v)
                    is Int -> e.putInt(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Float -> e.putFloat(k, v)
                }
                s.remove(k)
            }
        }
        // settings that no longer do anything
        for (k in RETIRED) s.remove(k)
        e.putBoolean(MIGRATED, true).apply()
        s.apply()
    }

    companion object {
        /** Settings that change how the keyboard is built (size, colors, layouts); the rest apply live. */
        fun rebuildsKeyboard(key: String) = key in LOOK || key.startsWith("key_ovr_")

        private val LOOK by lazy {
            setOf(THEME, STYLE, ACCENT, KEY_HEIGHT, KEY_GAP, ROW_GAP, KEY_RADIUS, LABEL_SCALE, HINT_SCALE, SHOW_HINTS, SHOW_ALL_HINTS, HINT_MODE, CAND_FONT,
                KEY_BORDERS, NUMBER_ROW, CAND_SCALE, BOTTOM_PADDING, CURSOR_LABELS, LANGUAGES, TOP_ROW_SCRIPTS, LAYOUT_RELOAD)
        }

        private const val MIGRATED = "migrated_v1"
        private val STATE_KEYS = setOf("emoji_recent", "kao_recent", "sym_recent", "emoji_tone")
        private val STATE_PREFIXES = listOf("seen_", "app_lang_")
        private val RETIRED = listOf("accent_enter", "toolbar", "zh_en_mix", "incognito", "learn_private", "voice_join_comma",
            "voice_max_sec", "ai_model", "vibrate_strength", "vibrate_ms")

        const val THEME = "theme"
        const val KEY_HEIGHT = "key_height"
        const val KEY_GAP = "key_gap"
        const val ROW_GAP = "row_gap"
        const val STYLE = "style"
        const val HINT_MODE = "hint_mode"
        const val CAND_FONT = "cand_font"
        const val SOLAR_TERMS = "solar_terms"
        const val KEY_RADIUS = "key_radius"
        const val LABEL_SCALE = "label_scale"
        const val HINT_SCALE = "hint_scale"
        const val SHOW_HINTS = "show_hints"
        const val SHOW_ALL_HINTS = "show_all_hints"
        const val KEY_BORDERS = "key_borders"
        const val NUMBER_ROW = "number_row"
        const val CAND_SCALE = "cand_scale"
        const val BOTTOM_PADDING = "bottom_padding"
        const val ACCENT = "accent"
        const val CURSOR_LABELS = "cursor_labels"
        /** Written by 布局文件 → 重新加载: the layouts are read again. */
        const val LAYOUT_RELOAD = "layout_reload"
        const val ANIMATIONS = "animations"
        const val ANIM_SPEED = "anim_speed"
        const val KEY_PREVIEW = "key_preview"
        const val VIBRATE = "vibrate"
        const val SOUND = "sound"
        const val SOUND_VOLUME = "sound_volume"
        const val FLICK_DP = "flick_dp"
        const val LONG_PRESS_MS = "long_press_ms"
        const val SPACE_SWIPE = "space_swipe"
        const val SPACE_LONG_PRESS = "space_long_press"
        const val CURSOR_STEP_DP = "cursor_step_dp"
        const val BACKSPACE_SWIPE = "backspace_swipe"
        const val BACKSPACE_REPEAT = "backspace_repeat"
        const val LP_CASE = "lp_case"
        const val LP_HINTS = "lp_hints"
        const val LP_DIGITS = "lp_digits"
        const val TOP_ROW_SCRIPTS = "top_row_scripts"
        const val DRAG_CANCEL = "drag_cancel"
        const val HOLD_CANCEL_MS = "hold_cancel_ms"
        const val LANGUAGES = "languages"
        const val AUTO_CAP = "auto_cap"
        const val DOUBLE_SPACE = "double_space"
        const val PER_APP_LANG = "per_app_lang"
        const val STRESS_MODE = "stress_mode"
        const val ZH_TRAD = "zh_trad"
        const val ZH_EMOJI = "zh_emoji"
        const val ZH_PREEDIT_INLINE = "zh_preedit_inline"
        const val ZH_PREDICT = "zh_predict"
        const val ZH_CORRECTION = "zh_correction"
        const val PINYIN_MODE = "pinyin_mode"
        const val BACKSPACE_RESELECT = "backspace_reselect"
        const val TYPO_MARK = "typo_mark"
        const val RARE_ANNOTATE = "rare_annotate"
        const val PACK_POETRY = "pack_poetry"
        const val GRAMMAR = "zh_grammar"
        const val TOUCH_ADAPT = "touch_adapt"
        /** 词库拓展包 catalogs subscribed to, one link a line. */
        const val DICT_FEEDS = "dict_feeds"
        const val TYPING_STATS = "typing_stats"
        const val CLIP_KEEP = "clip_keep"
        const val CLIP_SUGGEST = "clip_suggest"
        const val CLIP_MAX_CHARS = "clip_max_chars"
        const val CLIP_FRICTION = "clip_friction"
        const val CLIP_COLUMNS = "clip_columns"
        const val AUTO_PAIR = "auto_pair"
        const val SYMBOL_RETURN = "symbol_return"
        const val SMART_PUNCT = "smart_punct"
        const val SYMBOL_MEMORY = "symbol_memory"
        const val NUM_MODE = "num_mode"
        const val MATH_FORMAT = "math_format"
        const val MATH_CALC = "math_calc"
        const val NUMBER_FIELD_MATH = "number_field_math"
        const val EMOJI_AUTO_UPDATE = "emoji_auto_update"
        const val VOICE_ENGINE = "voice_engine"
        const val VOICE_MODEL = "voice_model"
        const val VOICE_SOURCE = "voice_source"
        const val VOICE_LANG = "voice_lang"
        const val VOICE_ZH_MODEL = "voice_zh_model"
        const val VOICE_EN_MODEL = "voice_en_model"
        const val VOICE_PUNCT = "voice_punct"
        const val VOICE_PANGU = "voice_pangu"
        const val VOICE_FILLERS = "voice_fillers"
        const val VOICE_PAUSE_MS = "voice_pause_ms"
        const val VOICE_COMMIT_PAUSE = "voice_commit_pause"
        const val VOICE_AUTO_STOP = "voice_auto_stop"
        const val VOICE_RETURN = "voice_return"
        const val AI_KEY = "ai_key"
        const val AI_PROVIDER = "ai_provider"
        const val TRANSLATE_TARGET = "translate_target"
        const val TRANSLATE_WIFI = "translate_wifi"
        const val TRANSLATE_ENGINE = "translate_engine"
        const val TOOLBAR_ITEMS = "toolbar_items"

        // ---- keys of [state] ----
        const val RECENT_EMOJI = "emoji_recent"
        const val RECENT_KAOMOJI = "kao_recent"
        const val RECENT_SYMBOLS = "sym_recent"
        const val EMOJI_TONES = "emoji_tone"
        /** Last emoji update check (ms). */
        const val EMOJI_CHECKED = "seen_emoji_checked"
        /** Written after the settings downloaded newer emoji: the keyboard reads them again. */
        const val EMOJI_RELOAD = "seen_emoji_reload"
    }
}
