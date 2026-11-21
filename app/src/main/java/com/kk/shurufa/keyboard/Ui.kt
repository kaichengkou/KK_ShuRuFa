package com.kk.shurufa.keyboard

import android.graphics.Typeface

/**
 * One type scale, one set of shapes and one spacing grid for every surface of the keyboard, so
 * panels drawn by different views read as one design: a single sans family (regular and medium,
 * never synthetic bold or serif), a short list of text sizes, radii derived from the key radius,
 * and a 4 dp grid.
 */
object Ui {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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
