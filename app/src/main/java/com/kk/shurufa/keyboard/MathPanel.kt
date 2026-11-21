package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.Prefs

/**
 * 学术键盘. A row of tabs; under it the tab's 4×4 function block on the left and the fixed
 * number block on the right; then the family's bottom row. Changing tabs only slides the
 * function block, so digits and operators never move.
 */
@SuppressLint("ViewConstructor")
class MathPanel(context: Context, private val prefs: Prefs, newKeyboard: () -> KeyboardView) : LinearLayout(context) {
    var theme: KbTheme
        get() = tabs.theme
        set(t) { tabs.theme = t; func.theme = t; pad.theme = t; bottom.theme = t }
    companion object {
        /** Key height of the academic rows, relative to the letters. */
        const val ROW = 0.9f
    }

    val tabs = TabStrip(context, prefs)
    val func = newKeyboard().apply { heightScale = ROW; padTopDp = 2f; padBottom = false }
    val pad = newKeyboard().apply { heightScale = ROW; padTopDp = 2f; padBottom = false }
    val bottom = newKeyboard().apply { padTopDp = prefs.keyGapDp; spaceSwipeOverride = "cursor"; setRows(listOf(FamilyRows.academic())) }
    private var shownPad: MathLayout? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        orientation = VERTICAL
        val body = LinearLayout(context).apply { orientation = HORIZONTAL }
        body.addView(func, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(pad, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Shows [tabId]; [slide] -1/1 brings the function block in from that side. */
    fun show(m: MathLayout, tabId: String, slide: Int = 0) {
        val tab = m.tabs.firstOrNull { it.id == tabId } ?: m.tabs.first()
        tabs.setTabs(m.tabs.map { it.id to it.name }, tab.id)
        if (shownPad !== m) { pad.setRows(m.pad, math = true); shownPad = m }
        func.setRows(tab.rows, math = true)
        if (slide != 0 && prefs.animations) {
            func.animate().cancel()
            func.translationX = slide * dp(28f); func.alpha = 0f
            func.animate().translationX(0f).alpha(1f).setDuration(Motion.dur(prefs, 220))
                .setInterpolator(Motion.ENTER).withLayer().start()
        }
    }
}
