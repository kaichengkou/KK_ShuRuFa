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
 */
data class KbTheme(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surfacePressed: Int,
    val soft: Int,          // translucent fill, drawn over background
    val softPressed: Int,
    val ink: Int,
    val inkSoft: Int,       // labels on soft keys and toolbar icons
    val hint: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val accentSoft: Int,    // translucent accent for active states
    val popup: Int,
    val shadow: Int,
    val divider: Int,
) {
    // names kept for code that predates the redesign
    val key get() = surface
    val keyFn get() = soft
    val keyPressed get() = surfacePressed
    val bar get() = background
    val tool get() = soft
    val border get() = shadow

    companion object {
        /** Accent presets: id to (light, dark). */
        val ACCENTS = linkedMapOf(
            "teal" to ("青" to (0xFF0E6A73.toInt() to 0xFF5BC3CB.toInt())),
            "indigo" to ("靛蓝" to (0xFF4652C2.toInt() to 0xFF9CA6FF.toInt())),
            "coral" to ("珊瑚" to (0xFFD35A3A.toInt() to 0xFFFF9C82.toInt())),
            "green" to ("松绿" to (0xFF2D7A4F.toInt() to 0xFF7ED39D.toInt())),
            "violet" to ("紫" to (0xFF7650C4.toInt() to 0xFFC2A5FF.toInt())),
            "graphite" to ("石墨" to (0xFF30363E.toInt() to 0xFFD2D7DE.toInt())),
            "system" to ("系统取色" to (0 to 0)),
        )

        private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((a shr s and 0xff) + ((b shr s and 0xff) - (a shr s and 0xff)) * t).toInt() shl s
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        private fun accentFor(context: Context, id: String, dark: Boolean): Int {
            if (id == "system" && Build.VERSION.SDK_INT >= 31) {
                return context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            }
            val pair = ACCENTS[id]?.second ?: ACCENTS["teal"]!!.second
            return if (dark) pair.second else pair.first
        }

        fun resolve(context: Context, prefs: Prefs): KbTheme {
            val night = when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            val accent = accentFor(context, prefs.accent, night)
            return if (night) {
                val ink = Color.parseColor("#ECEEF1")
                KbTheme(
                    dark = true,
                    background = Color.parseColor("#121417"),
                    surface = Color.parseColor("#2B2F35"),
                    surfacePressed = Color.parseColor("#3D424A"),
                    soft = withAlpha(ink, 0x17),
                    softPressed = withAlpha(ink, 0x30),
                    ink = ink,
                    inkSoft = Color.parseColor("#B9BEC6"),
                    hint = Color.parseColor("#7F8690"),
                    accent = accent,
                    accentPressed = mix(accent, Color.WHITE, 0.25f),
                    onAccent = Color.parseColor("#0C1013"),
                    accentSoft = withAlpha(accent, 0x33),
                    popup = Color.parseColor("#353A41"),
                    shadow = withAlpha(Color.BLACK, 0x40),
                    divider = withAlpha(ink, 0x14),
                )
            } else {
                val ink = Color.parseColor("#16191D")
                KbTheme(
                    dark = false,
                    background = Color.parseColor("#ECEEF2"),
                    surface = Color.WHITE,
                    surfacePressed = Color.parseColor("#DDE1E7"),
                    soft = withAlpha(ink, 0x0F),
                    softPressed = withAlpha(ink, 0x22),
                    ink = ink,
                    inkSoft = Color.parseColor("#454B54"),
                    hint = Color.parseColor("#9198A2"),
                    accent = accent,
                    accentPressed = mix(accent, Color.BLACK, 0.2f),
                    onAccent = Color.WHITE,
                    accentSoft = withAlpha(accent, 0x22),
                    popup = Color.WHITE,
                    shadow = withAlpha(Color.parseColor("#1D2B3A"), 0x1C),
                    divider = withAlpha(ink, 0x12),
                )
            }
        }
    }
}
