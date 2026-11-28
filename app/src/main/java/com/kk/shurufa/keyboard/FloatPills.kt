package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.max
import kotlin.math.min

/**
 * AI 标签: what the AI offers (云联想's continuations, or the AI 候选) in quiet translucent tabs
 * standing on the keyboard's top right edge, over the app, one per line, stacked upward. Nothing
 * here moves the candidates, and only the tabs take touches.
 */
@SuppressLint("ViewConstructor")
class FloatPills(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onPick: ((Int) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    private var right: List<String> = emptyList()
    private val appear = Tween(0f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ellipsizer = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val hits = Hits()
    private var pressed: String? = null
    /** Where the tabs are (in this view), for the window's touch region. */
    val tabRects = ArrayList<RectF>()

    val showing get() = right.isNotEmpty()

    /** Shows the AI's [lines] (none: hidden). */
    fun show(lines: List<String>) {
        if (lines == right) return
        val had = right.isNotEmpty()
        val rows = max(1, lines.size) != max(1, right.size)
        right = lines
        if (right.isNotEmpty() && !had) { appear.set(0f); appear.animateTo(1f, dur(Motion.POP_IN + 40), Motion.ENTER) }
        visibility = if (showing) VISIBLE else GONE
        if (rows) requestLayout()
        invalidate()
    }

    private val tabH get() = dp(36f)
    private val gap get() = dp(4f)
    private val textSize get() = sp(Ui.EMPHASIS)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = max(1, right.size)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (tabH * rows + gap * (rows - 1)).toInt())
    }

    /** A tab standing on the keyboard: rounded on top, square where it meets the bar. */
    private fun tab(c: Canvas, r: RectF, roundBottom: Boolean) {
        val rad = dp(9f)
        path.reset()
        path.addRoundRect(r, if (roundBottom) floatArrayOf(rad, rad, rad, rad, rad, rad, rad, rad)
            else floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
        c.drawPath(path, bg)
    }

    override fun onDraw(c: Canvas) {
        hits.clear(); tabRects.clear()
        val t = theme
        // translucent, so the app shows through: dark glass by day, light glass at night
        val fill = if (t.dark) Colors.alpha(0xFFFFFFFF.toInt(), 0x2E) else Colors.alpha(t.ink, 0x8C)
        val ink = if (t.dark) t.ink else 0xFFFFFFFF.toInt()
        val pad = dp(11f)
        val maxW = width * 0.62f
        tp.typeface = Ui.regular; tp.textSize = textSize; tp.textAlign = Paint.Align.LEFT
        ellipsizer.set(tp)
        val iconW = dp(19f)
        val a = appear.value
        var y = height.toFloat()
        for ((i, line) in right.withIndex()) {
            val avail = maxW - pad * 2 - iconW
            if (avail < dp(40f)) break
            val shown = TextUtils.ellipsize(line, ellipsizer, avail, TextUtils.TruncateAt.END).toString()
            val w = tp.measureText(shown) + pad * 2 + iconW
            val r = RectF(width - w, y - tabH, width.toFloat(), y)
            // rises into place
            c.save(); c.translate(0f, (1f - a) * dp(10f)); bg.alpha = 255
            bg.color = Colors.fade(if (pressed == "r$i") Colors.blend(fill, ink, 0.12f) else fill, a)
            tab(c, r, roundBottom = i > 0)
            ip.color = Colors.fade(ink, a * 0.8f); ip.style = Paint.Style.FILL
            Icons.draw(c, "ai", r.left + pad + dp(6f), r.centerY(), dp(13f), ip)
            tp.color = Colors.fade(ink, a)
            drawText(c, shown, r.left + pad + iconW, r.centerY(), tp)
            c.restore()
            hits.add("r$i", r); tabRects += r
            y -= tabH + gap
        }
        if (appear.running) invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                invalidate(); return pressed != null
            }
            MotionEvent.ACTION_MOVE -> if (pressed != null && hits.at(e.x, e.y) != pressed) { pressed = null; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null; invalidate()
                if (p != null && hits.at(e.x, e.y) == p) {
                    onFeedback?.invoke()
                    p.removePrefix("r").toIntOrNull()?.let { onPick?.invoke(it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
