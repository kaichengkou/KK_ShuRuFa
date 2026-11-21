package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.abs

/**
 * A row of tabs that share the width evenly. The highlight glides from the old tab to the new
 * one; tapping anywhere in a tab's slot selects it.
 */
@SuppressLint("ViewConstructor")
class TabStrip(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onTab: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    private var tabs: List<Pair<String, String>> = emptyList()
    private var current = ""
    private val pill = Spring(0f, stiffness = 750f, dampingRatio = 0.85f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setTabs(list: List<Pair<String, String>>, selected: String) {
        val first = tabs.isEmpty() || list != tabs
        tabs = list
        val i = list.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        pill.enabled = Motion.on(prefs)
        if (first || current.isEmpty()) pill.snap(i.toFloat()) else if (selected != current) pill.animateTo(i.toFloat())
        current = selected
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(40f).toInt())
    }

    private val slot get() = (width - dp(8f)) / tabs.size.coerceAtLeast(1)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        if (tabs.isEmpty()) return
        if (pill.running) { pill.step(); postInvalidateOnAnimation() }
        val w = slot
        val p = pill.value
        val px = dp(4f) + p * w
        val top = dp(5f); val bottom = height - dp(5f)
        bg.color = theme.accentSoft
        c.drawRoundRect(px + dp(3f), top, px + w - dp(3f), bottom, (bottom - top) / 2, (bottom - top) / 2, bg)
        tp.textSize = sp(Ui.BODY)
        tabs.forEachIndexed { i, (_, name) ->
            // the label takes the accent as the highlight arrives under it
            val near = (1f - abs(p - i)).coerceIn(0f, 1f)
            tp.typeface = if (near > 0.5f) Ui.medium else Ui.regular
            tp.color = Colors.blend(theme.inkSoft, theme.accent, near)
            drawText(c, name, dp(4f) + i * w + w / 2, height / 2f, tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && tabs.isNotEmpty()) {
            val i = ((e.x - dp(4f)) / slot).toInt().coerceIn(0, tabs.size - 1)
            val id = tabs[i].first
            if (id != current) { onFeedback?.invoke(); onTab?.invoke(id) }
        }
        return true
    }
}
