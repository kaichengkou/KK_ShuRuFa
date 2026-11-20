package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.TypedValue
import android.widget.FrameLayout

/**
 * 标准数字键盘: the keys, with a scrolling column of operators laid exactly over the space the
 * layout leaves for it (its spacer keys). Five operators fill the column; the rest scroll.
 */
@SuppressLint("ViewConstructor")
class NumPadView(context: Context, val keys: KeyboardView, val strip: GridPanel, private val visible: Int = 5) : FrameLayout(context) {
    private var items: List<String> = emptyList()

    init {
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(strip, LayoutParams(0, 0))
    }

    fun setItems(list: List<String>) { items = list; strip.setItems(list, resetScroll = false) }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        keys.layout(0, 0, r - l, b - t)
        val sp = keys.spacerRects
        if (sp.isEmpty()) { strip.layout(0, 0, 0, 0); return }
        val u = RectF(sp[0]); sp.forEach { u.union(it) }
        val gapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, strip.cellGapDp ?: 6f, resources.displayMetrics)
        // rows exactly as tall as fit [visible] cells in the column
        val rowPx = (u.height() - (visible - 1) * gapPx) / visible
        val rowDp = rowPx / resources.displayMetrics.density
        if (kotlin.math.abs(strip.rowHeightDp - rowDp) > 0.01f) { strip.rowHeightDp = rowDp; strip.setItems(items, resetScroll = false) }
        strip.measure(MeasureSpec.makeMeasureSpec(u.width().toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(u.height().toInt(), MeasureSpec.EXACTLY))
        strip.layout(u.left.toInt(), u.top.toInt(), u.right.toInt(), u.bottom.toInt())
    }
}
