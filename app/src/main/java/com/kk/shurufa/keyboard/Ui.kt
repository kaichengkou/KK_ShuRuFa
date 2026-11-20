package com.kk.shurufa.keyboard

import android.graphics.Typeface

/**
 * One type scale and one set of shapes for every surface of the keyboard, so panels drawn by
 * different views read as one design: a single sans family (regular and medium, never
 * synthetic bold or serif), five text sizes, and two corner radii derived from the key radius.
 */
object Ui {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** sp sizes */
    const val CAPTION = 10f     // notes above candidates, tags on cards, key hints
    const val SMALL = 11.5f     // section titles, secondary lines, preedit
    const val BODY = 13.5f      // buttons, tabs, menu items, panel labels
    const val TITLE = 15f       // panel titles
    const val LARGE = 18f       // candidates, symbols

    /** Larger containers (cards, the edit pad) round a little more than keys. */
    fun radiusLarge(keyRadius: Float, dp: Float) = keyRadius + 6f * dp
}
