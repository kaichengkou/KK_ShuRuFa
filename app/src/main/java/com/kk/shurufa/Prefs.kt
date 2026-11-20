package com.kk.shurufa

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user setting in one place. The settings screen and the input method both read
 * from here; the keyboard rebuilds itself when a value changes.
 */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("kk_prefs", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun f(key: String, def: Float) = sp.getFloat(key, def)
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def

    // Appearance
    val theme get() = s(THEME, "system")                 // system | light | dark
    val keyHeightDp get() = i(KEY_HEIGHT, 46)
    val keyGapDp get() = f(KEY_GAP, 5f)
    val keyRadiusDp get() = f(KEY_RADIUS, 7f)
    val labelScale get() = f(LABEL_SCALE, 1f)
    val hintScale get() = f(HINT_SCALE, 1f)
    val showHints get() = b(SHOW_HINTS, true)
    val showAllHints get() = b(SHOW_ALL_HINTS, false)
    val keyBorders get() = b(KEY_BORDERS, true)
    val accentEnter get() = b(ACCENT_ENTER, true)
    val numberRow get() = b(NUMBER_ROW, false)
    val toolbarVisible get() = b(TOOLBAR, true)
    val candidateScale get() = f(CAND_SCALE, 1f)
    val bottomPaddingDp get() = i(BOTTOM_PADDING, 4)

    val accent get() = s(ACCENT, "teal")                  // teal | indigo | coral | green | violet | graphite | system

    // Motion
    val animations get() = b(ANIMATIONS, true)
    val animSpeed get() = f(ANIM_SPEED, 1f)               // duration multiplier, lower is faster
    val keyPreview get() = b(KEY_PREVIEW, true)

    // Feedback
    val vibrate get() = b(VIBRATE, true)
    val vibrateStrength get() = i(VIBRATE_STRENGTH, 60)   // 1..255
    val vibrateMs get() = i(VIBRATE_MS, 14)
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

    // Languages
    val languages: List<String> get() = s(LANGUAGES, "zh,ru,en").split(",").filter { it.isNotBlank() }
    val autoCap get() = b(AUTO_CAP, true)
    val doubleSpacePeriod get() = b(DOUBLE_SPACE, true)
    val perAppLanguage get() = b(PER_APP_LANG, true)

    // Chinese
    val zhTraditional get() = b(ZH_TRAD, false)
    val zhEmoji get() = b(ZH_EMOJI, true)
    val zhEnglishMix get() = b(ZH_EN_MIX, true)
    val zhPreeditInline get() = b(ZH_PREEDIT_INLINE, false)
    val zhPredict get() = b(ZH_PREDICT, true)
    val zhCorrection get() = b(ZH_CORRECTION, true)
    val pinyinMode get() = b(PINYIN_MODE, false)
    val stressMode get() = b(STRESS_MODE, false)
    val clipKeep get() = i(CLIP_KEEP, 40)
    val clipSuggest get() = b(CLIP_SUGGEST, true)
    val backspaceReselect get() = b(BACKSPACE_RESELECT, true)
    val numMode get() = s(NUM_MODE, "simple")            // simple | sci
    val cursorLabels get() = s(CURSOR_LABELS, "icon")    // icon | both | text
    val typoMark get() = b(TYPO_MARK, true)
    val rareAnnotate get() = b(RARE_ANNOTATE, true)
    val packPoetry get() = b(PACK_POETRY, true)
    val symbolMemorySec get() = i(SYMBOL_MEMORY, 7)
    val topRowScripts get() = b(TOP_ROW_SCRIPTS, false)
    val clipMaxChars get() = i(CLIP_MAX_CHARS, 80)
    val clipFriction get() = f(CLIP_FRICTION, 1f)
    val clipColumns get() = i(CLIP_COLUMNS, 2)
    val emojiAutoUpdate get() = b(EMOJI_AUTO_UPDATE, true)
    val voiceEngine get() = s(VOICE_ENGINE, "whisper")
    val voiceModel get() = s(VOICE_MODEL, "small")
    val voiceSource get() = s(VOICE_SOURCE, "hf")
    val voiceLang get() = s(VOICE_LANG, "follow")
    val voiceMaxSec get() = i(VOICE_MAX_SEC, 60)
    val aiKey get() = s(AI_KEY, "")
    val aiModel get() = s(AI_MODEL, "claude-opus-5-5")
    val translateTarget get() = s(TRANSLATE_TARGET, "en")
    val translateWifiOnly get() = b(TRANSLATE_WIFI, false)
    /** "mlkit" or a provider id (see online.Llm). */
    val translateEngine get() = s(TRANSLATE_ENGINE, "mlkit")
    val aiProvider get() = s(AI_PROVIDER, "claude")
    val voiceZhModel get() = s(VOICE_ZH_MODEL, "sensevoice")
    val voicePunct get() = s(VOICE_PUNCT, "full")
    val voiceJoinComma get() = b(VOICE_JOIN_COMMA, true)
    val voicePangu get() = b(VOICE_PANGU, false)
    val voicePauseMs get() = i(VOICE_PAUSE_MS, 700)
    val voiceAutoStopSec get() = i(VOICE_AUTO_STOP, 3)

    // Symbols
    val autoPair get() = b(AUTO_PAIR, true)
    val symbolAutoReturn get() = b(SYMBOL_RETURN, true)
    val smartPunct get() = b(SMART_PUNCT, true)

    // Math
    val mathFormat get() = s(MATH_FORMAT, "uni")          // uni | tex
    val mathCalc get() = b(MATH_CALC, true)
    val numberFieldMath get() = b(NUMBER_FIELD_MATH, true)

    // Privacy
    val incognito get() = b(INCOGNITO, false)
    val learnInPrivate get() = b(LEARN_PRIVATE, false)

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

    fun appLanguage(pkg: String): String? = sp.getString("app_lang_$pkg", null)
    fun setAppLanguage(pkg: String, lang: String) = sp.edit().putString("app_lang_$pkg", lang).apply()

    companion object {
        const val THEME = "theme"
        const val KEY_HEIGHT = "key_height"
        const val KEY_GAP = "key_gap"
        const val KEY_RADIUS = "key_radius"
        const val LABEL_SCALE = "label_scale"
        const val HINT_SCALE = "hint_scale"
        const val SHOW_HINTS = "show_hints"
        const val SHOW_ALL_HINTS = "show_all_hints"
        const val KEY_BORDERS = "key_borders"
        const val ACCENT_ENTER = "accent_enter"
        const val NUMBER_ROW = "number_row"
        const val TOOLBAR = "toolbar"
        const val CAND_SCALE = "cand_scale"
        const val BOTTOM_PADDING = "bottom_padding"
        const val ANIMATIONS = "animations"
        const val ANIM_SPEED = "anim_speed"
        const val KEY_PREVIEW = "key_preview"
        const val VIBRATE = "vibrate"
        const val VIBRATE_STRENGTH = "vibrate_strength"
        const val VIBRATE_MS = "vibrate_ms"
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
        const val LANGUAGES = "languages"
        const val AUTO_CAP = "auto_cap"
        const val DOUBLE_SPACE = "double_space"
        const val PER_APP_LANG = "per_app_lang"
        const val ZH_TRAD = "zh_trad"
        const val ZH_EMOJI = "zh_emoji"
        const val ZH_EN_MIX = "zh_en_mix"
        const val ZH_PREEDIT_INLINE = "zh_preedit_inline"
        const val ZH_PREDICT = "zh_predict"
        const val ZH_CORRECTION = "zh_correction"
        const val PINYIN_MODE = "pinyin_mode"
        const val STRESS_MODE = "stress_mode"
        const val CLIP_KEEP = "clip_keep"
        const val CLIP_SUGGEST = "clip_suggest"
        const val BACKSPACE_RESELECT = "backspace_reselect"
        const val NUM_MODE = "num_mode"
        const val CURSOR_LABELS = "cursor_labels"
        const val TYPO_MARK = "typo_mark"
        const val RARE_ANNOTATE = "rare_annotate"
        const val PACK_POETRY = "pack_poetry"
        const val SYMBOL_MEMORY = "symbol_memory"
        const val TOP_ROW_SCRIPTS = "top_row_scripts"
        const val CLIP_MAX_CHARS = "clip_max_chars"
        const val CLIP_FRICTION = "clip_friction"
        const val CLIP_COLUMNS = "clip_columns"
        const val EMOJI_AUTO_UPDATE = "emoji_auto_update"
        const val VOICE_ENGINE = "voice_engine"
        const val VOICE_MODEL = "voice_model"
        const val VOICE_SOURCE = "voice_source"
        const val VOICE_LANG = "voice_lang"
        const val VOICE_MAX_SEC = "voice_max_sec"
        const val AI_KEY = "ai_key"
        const val AI_MODEL = "ai_model"
        const val TRANSLATE_TARGET = "translate_target"
        const val TRANSLATE_WIFI = "translate_wifi"
        const val TRANSLATE_ENGINE = "translate_engine"
        const val AI_PROVIDER = "ai_provider"
        const val VOICE_ZH_MODEL = "voice_zh_model"
        const val VOICE_PUNCT = "voice_punct"
        const val VOICE_JOIN_COMMA = "voice_join_comma"
        const val VOICE_PANGU = "voice_pangu"
        const val VOICE_PAUSE_MS = "voice_pause_ms"
        const val VOICE_AUTO_STOP = "voice_auto_stop"
        /** Last emoji update check (ms); a state, not a setting. */
        const val EMOJI_CHECKED = "seen_emoji_checked"
        const val ACCENT = "accent"
        const val AUTO_PAIR = "auto_pair"
        const val SYMBOL_RETURN = "symbol_return"
        const val SMART_PUNCT = "smart_punct"
        const val MATH_FORMAT = "math_format"
        const val MATH_CALC = "math_calc"
        const val NUMBER_FIELD_MATH = "number_field_math"
        const val INCOGNITO = "incognito"
        const val LEARN_PRIVATE = "learn_private"
    }
}
