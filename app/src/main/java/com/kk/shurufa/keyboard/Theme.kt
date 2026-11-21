package com.kk.shurufa.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import com.kk.shurufa.Prefs

/**
 * KK design language. Three levels of emphasis:
 *  - solid keys (letters, digits, space): the things you type, on an opaque surface;
 *  - soft keys (functions, symbols, mode switches): translucent ink over the background;
 *  - the action key (enter) and selections: filled with the accent color.
 * Every panel, chip, card and menu reuses these same fills, so nothing introduces a color
 * of its own.
 */
data class KbTheme(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surfacePressed: Int,
    /** Translucent fill, drawn over the background. */
    val soft: Int,
    val softPressed: Int,
    val ink: Int,
    /** Labels on soft keys, toolbar icons, secondary text. */
    val inkSoft: Int,
    val hint: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    /** Translucent accent for active states and tags. */
    val accentSoft: Int,
    val popup: Int,
    val shadow: Int,
    val divider: Int,
) {
    companion object {
        /** Accent presets: id to (name, (light, dark)). */
        val ACCENTS = linkedMapOf(
            "teal" to ("青" to (0xFF0E6A73.toInt() to 0xFF5BC3CB.toInt())),
            "indigo" to ("靛蓝" to (0xFF4652C2.toInt() to 0xFF9CA6FF.toInt())),
            "coral" to ("珊瑚" to (0xFFD35A3A.toInt() to 0xFFFF9C82.toInt())),
            "green" to ("松绿" to (0xFF2D7A4F.toInt() to 0xFF7ED39D.toInt())),
            "violet" to ("紫" to (0xFF7650C4.toInt() to 0xFFC2A5FF.toInt())),
            "graphite" to ("石墨" to (0xFF30363E.toInt() to 0xFFD2D7DE.toInt())),
            "system" to ("系统取色" to (0 to 0)),
        )

        /** The accent shown for preset [id] (the settings swatches use it too). */
        fun accentFor(context: Context, id: String, dark: Boolean): Int {
            if (id == "system" && Build.VERSION.SDK_INT >= 31) {
                return context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            }
            val pair = ACCENTS[id]?.second ?: ACCENTS.getValue("teal").second
            return if (dark) pair.second else pair.first
        }

        fun isDark(context: Context, prefs: Prefs) = when (prefs.theme) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        fun resolve(context: Context, prefs: Prefs): KbTheme {
            val night = isDark(context, prefs)
            val accent = accentFor(context, prefs.accent, night)
            return if (night) {
                val ink = Color.parseColor("#ECEEF1")
                KbTheme(
                    dark = true,
                    background = Color.parseColor("#121417"),
                    surface = Color.parseColor("#2A2E34"),
                    surfacePressed = Color.parseColor("#3B4048"),
                    soft = Colors.alpha(ink, 0x17),
                    softPressed = Colors.alpha(ink, 0x30),
                    ink = ink,
                    inkSoft = Color.parseColor("#B9BEC6"),
                    hint = Color.parseColor("#80868F"),
                    accent = accent,
                    accentPressed = Colors.blend(accent, Color.WHITE, 0.25f),
                    onAccent = Color.parseColor("#0C1013"),
                    accentSoft = Colors.alpha(accent, 0x33),
                    popup = Color.parseColor("#353A41"),
                    shadow = Colors.alpha(Color.BLACK, 0x40),
                    divider = Colors.alpha(ink, 0x14),
                )
            } else {
                val ink = Color.parseColor("#16191D")
                KbTheme(
                    dark = false,
                    background = Color.parseColor("#ECEEF2"),
                    surface = Color.WHITE,
                    surfacePressed = Color.parseColor("#DCE0E6"),
                    soft = Colors.alpha(ink, 0x0F),
                    softPressed = Colors.alpha(ink, 0x22),
                    ink = ink,
                    inkSoft = Color.parseColor("#454B54"),
                    hint = Color.parseColor("#8F96A0"),
                    accent = accent,
                    accentPressed = Colors.blend(accent, Color.BLACK, 0.2f),
                    onAccent = Color.WHITE,
                    accentSoft = Colors.alpha(accent, 0x26),
                    popup = Color.WHITE,
                    shadow = Colors.alpha(Color.parseColor("#1D2B3A"), 0x1C),
                    divider = Colors.alpha(ink, 0x12),
                )
            }
        }
    }
}

/** Color arithmetic on packed ARGB ints, shared by every view. */
object Colors {
    /** [c] with its alpha replaced by [a] (0..255). */
    fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** [c] with its own alpha scaled by [f] (0..1): fades a translucent color without making it opaque. */
    fun fade(c: Int, f: Float) = alpha(c, ((c ushr 24) * f.coerceIn(0f, 1f)).toInt())

    /** Linear mix of two colors, alpha included; [t] 0 is [a], 1 is [b]. */
    fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = (((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
