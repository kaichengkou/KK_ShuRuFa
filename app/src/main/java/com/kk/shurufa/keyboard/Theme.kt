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
    /** Status marks (a dot, a short word): fine, and worth a look. The same two everywhere they show. */
    val good: Int get() = if (dark) 0xFF6CC497.toInt() else 0xFF3E9B6A.toInt()
    val caution: Int get() = if (dark) 0xFFE8B260.toInt() else 0xFFC98A1E.toInt()

    companion object {
        /**
         * Accent presets: id to (name, (light, dark)). Named and mixed after 中国传统色; each dark
         * form is a lighter, quieter tone of the same color, so the enter key does not glare at night.
         */
        val ACCENTS = linkedMapOf(
            "teal" to ("石青" to (0xFF0E6A73.toInt() to 0xFF5AAFB5.toInt())),
            "indigo" to ("黛蓝" to (0xFF3A4C7A.toInt() to 0xFF97A6CF.toInt())),
            "cinnabar" to ("朱砂" to (0xFFB0382B.toInt() to 0xFFE08876.toInt())),
            "rouge" to ("胭脂" to (0xFF9A2E4D.toInt() to 0xFFDB93A8.toInt())),
            "gamboge" to ("藤黄" to (0xFFA8720E.toInt() to 0xFFE2B866.toInt())),
            "green" to ("竹青" to (0xFF3B6E52.toInt() to 0xFF8EC0A2.toInt())),
            "graphite" to ("墨" to (0xFF2F3237.toInt() to 0xFFD6D1C8.toInt())),
            "system" to ("系统取色" to (0 to 0)),
            "custom" to ("自定义" to (0 to 0)),
        )

        /** The 自定义 accent: [hue] at the lightness and strength of the presets, light and dark. */
        fun customAccent(hue: Int, dark: Boolean): Int =
            if (dark) hsl(hue.toFloat(), 0.42f, 0.64f) else hsl(hue.toFloat(), 0.62f, 0.32f)

        private fun hsl(h: Float, s: Float, l: Float): Int = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, l))

        /** The accent shown for preset [id] (the settings swatches use it too). */
        fun accentFor(context: Context, id: String, dark: Boolean, prefs: Prefs? = null): Int {
            if (id == "custom") return customAccent((prefs ?: Prefs(context)).accentHue, dark)
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

        /**
         * 临时模式: 黛蓝 night, a muted slate blue with a moonlit accent: plainly not the usual
         * keyboard (and the space key says 临时), but in the same quiet palette as the rest.
         */
        fun incognito(): KbTheme {
            val ink = Color.parseColor("#E4E7EF")
            val accent = Color.parseColor("#9AA6D4")
            return KbTheme(
                dark = true,
                background = Color.parseColor("#161A26"),
                surface = Color.parseColor("#272D3E"),
                surfacePressed = Color.parseColor("#353C51"),
                soft = Colors.alpha(ink, 0x17),
                softPressed = Colors.alpha(ink, 0x30),
                ink = ink,
                inkSoft = Color.parseColor("#B7BDCC"),
                hint = Color.parseColor("#858DA3"),
                accent = accent,
                accentPressed = Colors.blend(accent, Color.WHITE, 0.25f),
                onAccent = Color.parseColor("#161A26"),
                accentSoft = Colors.alpha(accent, 0x33),
                popup = Color.parseColor("#2E3547"),
                shadow = Colors.alpha(Color.BLACK, 0x50),
                divider = Colors.alpha(ink, 0x16),
            )
        }

        fun resolve(context: Context, prefs: Prefs): KbTheme {
            val night = isDark(context, prefs)
            // 纸墨 starts in 朱砂, the color of a seal on paper, until another accent is picked
            val id = if (prefs.style == "paper" && !prefs.sp.contains(Prefs.ACCENT)) "cinnabar" else prefs.accent
            val accent = accentFor(context, id, night, prefs)
            Ui.useFont(context, prefs.font)
            if (prefs.incognito) return incognito()
            val base = if (prefs.style == "paper") paper(night, accent) else plain(night, accent)
            return base.tuned(prefs.themeWarmth / 100f, prefs.themeShade / 100f)
        }

        private fun plain(night: Boolean, accent: Int): KbTheme {
            return if (night) {
                val ink = Color.parseColor("#ECEEF1")
                KbTheme(
                    dark = true,
                    background = Color.parseColor("#0E1013"),
                    surface = Color.parseColor("#2C3138"),
                    surfacePressed = Color.parseColor("#3F454E"),
                    soft = Colors.alpha(ink, 0x17),
                    softPressed = Colors.alpha(ink, 0x30),
                    ink = ink,
                    inkSoft = Color.parseColor("#C4C9D0"),
                    hint = Color.parseColor("#8C929B"),
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
                    hint = Color.parseColor("#737A86"),   // 3.6:1 on the background (was 2.6:1)
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

/**
 * 纸墨: rice paper and ink. Warm paper under keys of a lighter, smoother paper, brown-black ink,
 * hints in the grey of diluted ink; at night, ink-stone black with keys like a wet brush stroke.
 * The views add a faint paper grain to its background.
 */
private fun paper(night: Boolean, accent: Int): KbTheme = if (night) {
    val ink = Color.parseColor("#F3EEE5")
    KbTheme(
        dark = true,
        background = Color.parseColor("#121110"),
        surface = Color.parseColor("#312E2A"),
        surfacePressed = Color.parseColor("#45413B"),
        soft = Colors.alpha(ink, 0x16),
        softPressed = Colors.alpha(ink, 0x2E),
        ink = ink,
        inkSoft = Color.parseColor("#CDC5B8"),
        hint = Color.parseColor("#988F83"),
        accent = accent,
        accentPressed = Colors.blend(accent, Color.WHITE, 0.2f),
        onAccent = Color.parseColor("#1A1513"),
        accentSoft = Colors.alpha(accent, 0x30),
        popup = Color.parseColor("#3B3833"),
        shadow = Colors.alpha(Color.BLACK, 0x48),
        divider = Colors.alpha(ink, 0x14),
    )
} else {
    val ink = Color.parseColor("#2A2521")
    KbTheme(
        dark = false,
        background = Color.parseColor("#E9E3D7"),
        surface = Color.parseColor("#FBF8F2"),
        surfacePressed = Color.parseColor("#E5DED0"),
        soft = Colors.alpha(Color.parseColor("#5B4A36"), 0x14),
        softPressed = Colors.alpha(Color.parseColor("#5B4A36"), 0x28),
        ink = ink,
        inkSoft = Color.parseColor("#5A5149"),
        hint = Color.parseColor("#7D7367"),   // warm grey-brown, readable on rice paper
        accent = accent,
        accentPressed = Colors.blend(accent, Color.BLACK, 0.18f),
        onAccent = Color.parseColor("#FFF9F0"),
        accentSoft = Colors.alpha(accent, 0x24),
        popup = Color.parseColor("#FBF8F2"),
        shadow = Colors.alpha(Color.parseColor("#4A3A28"), 0x22),
        divider = Colors.alpha(ink, 0x14),
    )
}

/**
 * 主题微调: [warmth] (−0.5…0.5) moves the keyboard's own colors (not the accent) toward a neutral
 * grey or a warmer tone of the same lightness, so 纸墨 can be made less yellow or 简约 a little
 * warmer; [shade] (−0.5…0.5) makes the background lighter or deeper against the keys.
 */
internal fun KbTheme.tuned(warmth: Float, shade: Float): KbTheme {
    if (warmth == 0f && shade == 0f) return this
    fun w(c: Int): Int {
        if (warmth == 0f) return c
        val a = c ushr 24
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        // cooler: toward the grey of the same lightness; warmer: toward paper under lamplight
        val target = if (warmth < 0) intArrayOf(y.toInt(), y.toInt(), y.toInt())
            else intArrayOf((y * 1.07f).toInt(), (y * 1.0f).toInt(), (y * 0.84f).toInt())
        val t = kotlin.math.abs(warmth) * 2f
        fun ch(v: Int, to: Int) = (v + (to.coerceIn(0, 255) - v) * t).toInt().coerceIn(0, 255)
        return (a shl 24) or (ch(r, target[0]) shl 16) or (ch(g, target[1]) shl 8) or ch(b, target[2])
    }
    val bg = w(background).let { c ->
        if (shade == 0f) c else Colors.blend(c, if (shade > 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE, kotlin.math.abs(shade) * (if (dark) 0.3f else 0.18f))
    }
    return copy(
        background = bg, surface = w(surface), surfacePressed = w(surfacePressed),
        soft = w(soft), softPressed = w(softPressed), ink = w(ink), inkSoft = w(inkSoft), hint = w(hint),
        popup = w(popup), divider = w(divider),
    )
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
