package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A vertically scrolling grid of text cells. [columns] > 0 gives fixed columns; 0 lays cells out
 * as a flow (each cell as wide as its text), used for the expanded candidate list.
 */
@SuppressLint("ViewConstructor")
class GridPanel(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var columns = 5
    var rowHeightDp = 44f
    var textSp = 19f
    var cellStyle = CellStyle.KEY
    var onItem: ((Int) -> Unit)? = null
    var onItemLong: ((Int) -> Unit)? = null
    var selectedIndex = -1
    var marks: Set<Int> = emptySet()

    enum class CellStyle { KEY, RAIL, ROW }

    private var items: List<String> = emptyList()
    private val cells = mutableListOf<RectF>()
    private var contentH = 0f
    private var scrollPos = 0f
    private val scroller = OverScroller(context)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val appear = Tween(1f)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setItems(list: List<String>, resetScroll: Boolean = true) {
        items = list
        if (resetScroll) { scrollPos = 0f; scroller.forceFinished(true) }
        layoutCells()
        if (prefs.animations && resetScroll) { appear.set(0f); appear.animateTo(1f, (160 * prefs.animSpeed).toLong()) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutCells()

    private fun layoutCells() {
        cells.clear()
        if (width == 0) return
        val pad = dp(5f)
        val g = dp(5f)
        val rh = dp(rowHeightDp)
        tp.textSize = sp(textSp)
        if (columns > 0) {
            val cw = (width - 2 * pad - g * (columns - 1)) / columns
            items.forEachIndexed { i, _ ->
                val r = i / columns; val c = i % columns
                val x = pad + c * (cw + g); val y = pad + r * (rh + g)
                cells += RectF(x, y, x + cw, y + rh)
            }
        } else {
            var x = pad; var y = pad
            items.forEach { s ->
                val w = max(dp(48f), tp.measureText(s) + dp(24f)).coerceAtMost(width - 2 * pad)
                if (x + w > width - pad) { x = pad; y += rh + g }
                cells += RectF(x, y, x + w, y + rh)
                x += w + g
            }
        }
        contentH = (cells.maxOfOrNull { it.bottom } ?: 0f) + pad
    }

    private val maxScroll get() = max(0f, contentH - height)

    override fun onDraw(c: Canvas) {
        c.drawColor(if (cellStyle == CellStyle.RAIL) theme.bar else theme.background)
        if (scroller.computeScrollOffset()) { scrollPos = scroller.currY.toFloat(); postInvalidateOnAnimation() }
        val a = appear.value
        if (appear.running) postInvalidateOnAnimation()
        c.save()
        c.translate(0f, -scrollPos + (1 - a) * dp(8f))
        tp.textSize = sp(textSp)
        items.forEachIndexed { i, s ->
            val r = cells.getOrNull(i) ?: return@forEachIndexed
            if (r.bottom < scrollPos || r.top > scrollPos + height) return@forEachIndexed
            val sel = i == selectedIndex
            when (cellStyle) {
                CellStyle.KEY, CellStyle.ROW -> {
                    bg.color = if (i == pressed) theme.keyPressed else theme.key
                    bg.alpha = (255 * a).toInt()
                    c.drawRoundRect(r, dp(7f), dp(7f), bg)
                    if (i in marks) {
                        bg.color = theme.accent
                        c.drawRoundRect(r.left + dp(8f), r.bottom - dp(3f), r.right - dp(8f), r.bottom - dp(1.5f), dp(1f), dp(1f), bg)
                    }
                }
                CellStyle.RAIL -> if (sel) {
                    bg.color = theme.background
                    c.drawRect(r.left - dp(5f), r.top - dp(2.5f), r.right + dp(5f), r.bottom + dp(2.5f), bg)
                }
            }
            tp.color = if (cellStyle == CellStyle.RAIL && sel) theme.accent else theme.ink
            tp.alpha = (255 * a).toInt()
            tp.isFakeBoldText = cellStyle == CellStyle.RAIL && sel
            val size = sp(textSp)
            tp.textSize = size
            val maxW = r.width() - dp(8f)
            val tw = tp.measureText(s)
            if (tw > maxW) tp.textSize = size * maxW / tw
            if (cellStyle == CellStyle.ROW) {
                tp.textAlign = Paint.Align.LEFT
                val fm = tp.fontMetrics
                c.drawText(ellipsize(s, maxW), r.left + dp(12f), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
                tp.textAlign = Paint.Align.CENTER
            } else {
                val fm = tp.fontMetrics
                c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
            }
        }
        c.restore()
    }

    private fun ellipsize(s: String, w: Float): String {
        val one = s.replace('\n', ' ')
        if (tp.measureText(one) <= w) return one
        var end = one.length
        while (end > 1 && tp.measureText(one, 0, end) + tp.measureText("…") > w) end--
        return one.substring(0, end) + "…"
    }

    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressed = -1
    private val longRunnable = Runnable {
        if (pressed >= 0 && !dragging) { onItemLong?.invoke(pressed); pressed = -1; invalidate() }
    }

    private fun hit(x: Float, y: Float): Int = cells.indexOfFirst { it.contains(x, y + scrollPos) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downY = e.y; lastY = e.y; dragging = false
                pressed = hit(e.x, e.y)
                if (onItemLong != null) handler.postDelayed(longRunnable, 450)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > slop) { dragging = true; pressed = -1; handler.removeCallbacks(longRunnable); invalidate() }
                if (dragging) {
                    scrollPos = (scrollPos - (e.y - lastY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = -(vt?.yVelocity ?: 0f)
                    scroller.fling(0, scrollPos.toInt(), 0, v.toInt(), 0, 0, 0, maxScroll.toInt())
                    postInvalidateOnAnimation()
                } else if (pressed >= 0) {
                    val i = pressed
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onItem?.invoke(i)
                }
                pressed = -1
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longRunnable); pressed = -1; vt?.recycle(); vt = null; invalidate() }
        }
        return true
    }
}
