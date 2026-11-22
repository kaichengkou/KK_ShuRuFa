package com.kk.shurufa.keyboard

import android.graphics.Typeface

/**
 * One type scale, one set of shapes and one spacing grid for every surface of the keyboard, so
 * panels drawn by different views read as one design: one font family for keys, candidates and
 * panels alike (regular and medium), a short list of text sizes, radii derived from the key
 * radius, and a 4 dp grid.
 */
object Ui {
    var regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL); private set
    var medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); private set
    private var fontId = "sans"
    private var fontStamp = 0L

    /** Where a font file the user chose is kept. */
    fun fontFile(context: android.content.Context) = java.io.File(context.filesDir, "kk_font")

    /**
     * 字体: sans (黑体, the system's), serif (宋体: Android pairs it with Noto Serif CJK) or custom
     * (a .ttf / .otf the user chose). Set before the views are built; they read it as they draw.
     */
    fun useFont(context: android.content.Context, id: String) {
        val file = fontFile(context)
        val stamp = if (id == "custom") file.lastModified() else 0L
        if (id == fontId && stamp == fontStamp) return
        fontId = id; fontStamp = stamp
        val base: Typeface? = when (id) {
            "serif" -> Typeface.SERIF
            "custom" -> runCatching { Typeface.createFromFile(file) }.getOrNull()
            else -> null
        }
        if (base == null) {
            regular = Typeface.create("sans-serif", Typeface.NORMAL); medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            return
        }
        regular = Typeface.create(base, Typeface.NORMAL)
        medium = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(base, 500, false) else Typeface.create(base, Typeface.BOLD)
    }

    // ---- type (sp) ----
    /** Hints in key corners. */
    const val HINT = 10.5f
    /** Notes above candidates, tags on cards, group labels. */
    const val CAPTION = 11f
    /** Section titles, secondary lines, the pinyin above the candidates. */
    const val SMALL = 12f
    /** Chips, buttons, tabs, menu items, panel text. */
    const val BODY = 14f
    /** Emphasized single lines: results, statuses. */
    const val TITLE = 16f
    /** Candidates and symbol cells. */
    const val CANDIDATE = 18f
    /** Words on function keys (!#1, ABC, 符号, 空格). */
    const val KEY_WORD = 14.5f
    /** Letters and digits. */
    const val KEY = 22f
    /** The bubble above a pressed key. */
    const val PREVIEW = 27f

    // ---- shapes (dp) ----
    /** Chips and pill buttons. */
    const val CHIP_H = 32f
    /** Bigger action buttons inside cards (替换, 上屏, 完成). */
    const val BUTTON_H = 34f
    /** Horizontal padding inside a chip. */
    const val CHIP_PAD = 14f
    /** Icon size in chips and small buttons. */
    const val CHIP_ICON = 16f
    /** Icons on the toolbar. */
    const val TOOL_ICON = 22f
    /** Icons on function keys. */
    const val KEY_ICON = 21f
    /** Gap between chips in a row. */
    const val CHIP_GAP = 8f
    /** Margin of panel content from the keyboard's edges. */
    const val EDGE = 10f

    /** Larger containers (cards, sheets, menus) round a little more than keys. */
    fun radiusLarge(keyRadius: Float, dp: Float) = keyRadius + 6f * dp
}
