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
    val numberRow get() = b(NUMBER_ROW, false)
    val toolbarVisible get() = b(TOOLBAR, true)
    val candidateScale get() = f(CAND_SCALE, 1f)
    val bottomPaddingDp get() = i(BOTTOM_PADDING, 4)

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
    val backspaceSwipe get() = b(BACKSPACE_SWIPE, true)
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
