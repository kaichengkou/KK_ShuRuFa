package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.abs

/**
 * A row of tabs that share the width evenly, or, [compact], that are as wide as their names and
 * line up from the left. The highlight glides from the old tab to the new one; tapping anywhere in
 * a tab's slot selects it.
 */
@SuppressLint("ViewConstructor")
class TabStrip(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onTab: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    /** Tabs as wide as their names, from the left, in a lower row (filters rather than pages). */
    var compact = false

    private var tabs: List<Pair<String, String>> = emptyList()
    private var current = ""
    private val pill = Spring(0f, stiffness = 1000f, dampingRatio = 0.76f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    /** Measures compact tabs, in one weight so their widths do not change as the highlight moves. */
    private val mp = Paint(Paint.ANTI_ALIAS_FLAG)

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
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (compact) 36f else 40f).toInt())
    }

    private val slot get() = (width - dp(8f)) / tabs.size.coerceAtLeast(1)

    /** Left edge of tab [i]; one past the last gives the right edge of the last. */
    private fun edge(i: Int): Float {
        if (!compact) return dp(4f) + i * slot
        mp.textSize = sp(Ui.SMALL + 1f); mp.typeface = Ui.medium
        var x = dp(Ui.EDGE - 4f)
        for (k in 0 until i.coerceAtMost(tabs.size)) x += mp.measureText(tabs[k].second) + dp(26f)
        return x
    }

    /** Where the highlight is at position [p] between tabs: left and right, interpolated. */
    private fun at(p: Float, f: (Int) -> Float): Float {
        val i = p.toInt().coerceIn(0, tabs.size - 1)
        val j = (i + 1).coerceAtMost(tabs.size - 1)
        val k = (p - i).coerceIn(0f, 1f)
        return f(i) + (f(j) - f(i)) * k
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        if (tabs.isEmpty()) return
        if (pill.running) { pill.step(); postInvalidateOnAnimation() }
        val p = pill.value
        val top = dp(if (compact) 4f else 5f); val bottom = height - dp(if (compact) 4f else 5f)
        val l = at(p) { edge(it) } + dp(3f)
        val r = at(p) { edge(it + 1) } - dp(3f)
        bg.color = theme.accentSoft
        c.drawRoundRect(l, top, r, bottom, (bottom - top) / 2, (bottom - top) / 2, bg)
        tp.textSize = sp(if (compact) Ui.SMALL + 1f else Ui.BODY)
        tabs.forEachIndexed { i, (_, name) ->
            // the label takes the accent as the highlight arrives under it
            val near = (1f - abs(p - i)).coerceIn(0f, 1f)
            tp.typeface = if (near > 0.5f) Ui.medium else Ui.regular
            tp.color = Colors.blend(theme.inkSoft, theme.accent, near)
            val a = edge(i); val b = edge(i + 1)
            drawText(c, name, (a + b) / 2, height / 2f, tp)
        }
    }

    /**
     * A vertical drag on the row (empty part included) moves the sheet the row sits in: phase 0
     * begins at the raw y, 1 follows it, 2 ends with the velocity. Taps pick a tab.
     */
    var onVerticalDrag: ((phase: Int, rawY: Float, velocity: Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** The tab under [x], or -1 (compact tabs end where their names do). */
    private fun tabAt(x: Float): Int {
        if (tabs.isEmpty()) return -1
        if (compact && (x > edge(tabs.size) + dp(4f) || x < edge(0) - dp(4f))) return -1
        return (0 until tabs.size).firstOrNull { x < edge(it + 1) } ?: (tabs.size - 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (onVerticalDrag == null && tabAt(e.x) < 0) return false
                downX = e.x; downY = e.y; dragging = false
                vt?.recycle(); vt = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.y - downY
                if (!dragging && onVerticalDrag != null && abs(dy) > slop && abs(dy) > abs(e.x - downX)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onVerticalDrag?.invoke(0, e.rawY - dy, 0f)
                }
                if (dragging) onVerticalDrag?.invoke(1, e.rawY, 0f)
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    onVerticalDrag?.invoke(2, e.rawY, vt?.yVelocity ?: 0f)
                } else if (abs(e.x - downX) < slop * 2) {
                    val i = tabAt(e.x)
                    if (i >= 0) { val id = tabs[i].first; if (id != current) { onFeedback?.invoke(); onTab?.invoke(id) } }
                }
                vt?.recycle(); vt = null; dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onVerticalDrag?.invoke(2, e.rawY, 0f)
                vt?.recycle(); vt = null; dragging = false
            }
        }
        return true
    }
}
