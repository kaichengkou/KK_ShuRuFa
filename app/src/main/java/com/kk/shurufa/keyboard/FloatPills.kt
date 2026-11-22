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
 * 浮条: what sits on the keyboard's top edge, over the app, in quiet translucent tabs. On the left
 * the pinyin being typed (… ni'hao, with a pencil: a tap edits it), so the candidate row below is
 * all words; on the right what the AI offers (云联想's continuations, the AI 候选), one tab each,
 * stacked upward. Nothing here moves the candidates, and only the tabs take touches.
 */
@SuppressLint("ViewConstructor")
class FloatPills(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onLeft: (() -> Unit)? = null
    var onRight: ((Int) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    private var left = ""
    private var leftEditable = false
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

    val showing get() = left.isNotEmpty() || right.isNotEmpty()

    /** [left]: the pinyin (or a label like 按原拼音), [editable] when a tap edits it; [right]: AI lines. */
    fun show(left: String, editable: Boolean, right: List<String>) {
        if (left == this.left && right == this.right && editable == leftEditable) return
        val had = this.right.isNotEmpty()
        val rows = max(1, right.size) != max(1, this.right.size)
        this.left = left; leftEditable = editable; this.right = right
        if (right.isNotEmpty() && !had) { appear.set(0f); appear.animateTo(1f, dur(Motion.POP_IN + 40), Motion.ENTER) }
        visibility = if (showing) VISIBLE else GONE
        if (rows) requestLayout()
        invalidate()
    }

    private val tabH get() = dp(46f)
    private val gap get() = dp(4f)
    private val textSize get() = sp(Ui.TITLE + 2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = max(1, right.size)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (tabH * rows + gap * (rows - 1)).toInt())
    }

    /** A tab standing on the keyboard: rounded on top, square where it meets the bar. */
    private fun tab(c: Canvas, r: RectF, roundBottom: Boolean) {
        val rad = dp(10f)
        path.reset()
        path.addRoundRect(r, if (roundBottom) floatArrayOf(rad, rad, rad, rad, rad, rad, rad, rad)
            else floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
        c.drawPath(path, bg)
    }

    override fun onDraw(c: Canvas) {
        hits.clear(); tabRects.clear()
        val t = theme
        // translucent, so the app shows through: dark glass by day, light glass at night
        val fill = if (t.dark) Colors.alpha(0xFFFFFFFF.toInt(), 0x2E) else Colors.alpha(0xFF3A3A3A.toInt(), 0x8C)
        val ink = if (t.dark) t.ink else 0xFFFFFFFF.toInt()
        val pad = dp(14f)
        val maxW = width * 0.62f
        tp.typeface = Ui.regular; tp.textSize = textSize; tp.textAlign = Paint.Align.LEFT
        ellipsizer.set(tp)
        // the AI's line keeps its room first; the pinyin takes what is left, cut at its start
        val iconW = dp(24f)
        val firstW = right.firstOrNull()?.let { min(tp.measureText(it) + pad * 2 + iconW, maxW) } ?: 0f
        if (left.isNotEmpty()) {
            val pencil = if (leftEditable) dp(26f) else 0f
            val avail = min(maxW, width - firstW - dp(10f)) - pad * 2 - pencil
            // the end of the pinyin matters (where typing goes on): cut at the start
            val shown = if (tp.measureText(left) <= avail) left else "…" + TextUtils.ellipsize(left.reversed(), ellipsizer, avail - tp.measureText("…"),
                TextUtils.TruncateAt.END).toString().trimEnd('…').reversed()
            val w = tp.measureText(shown) + pad * 2 + pencil
            val r = RectF(0f, height - tabH, w, height.toFloat())
            bg.color = if (pressed == "left") Colors.blend(fill, ink, 0.12f) else fill
            tab(c, r, roundBottom = false)
            tp.color = ink
            drawText(c, shown, r.left + pad, r.centerY(), tp)
            if (leftEditable) drawPencil(c, r.right - pad - dp(9f), r.centerY(), ink)
            hits.add("left", r); tabRects += r
        }
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
            Icons.draw(c, "ai", r.left + pad + dp(8f), r.centerY(), dp(16f), ip)
            tp.color = Colors.fade(ink, a)
            drawText(c, shown, r.left + pad + iconW, r.centerY(), tp)
            c.restore()
            hits.add("r$i", r); tabRects += r
            y -= tabH + gap
        }
        if (appear.running) invalidate()
    }

    private fun drawPencil(c: Canvas, cx: Float, cy: Float, color: Int) {
        ip.color = color; ip.style = Paint.Style.STROKE; ip.strokeWidth = dp(1.8f); ip.strokeCap = Paint.Cap.ROUND; ip.strokeJoin = Paint.Join.ROUND
        val s = dp(8f)
        path.reset()
        // a pencil leaning right: body, then the tip at the lower left
        path.moveTo(cx - s * 0.55f, cy + s * 0.55f)
        path.lineTo(cx + s * 0.55f, cy - s * 0.55f)
        path.lineTo(cx + s * 0.85f, cy - s * 0.25f)
        path.lineTo(cx - s * 0.25f, cy + s * 0.85f)
        path.lineTo(cx - s * 0.8f, cy + s * 0.8f)
        path.close()
        c.drawPath(path, ip)
        ip.style = Paint.Style.FILL
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)?.takeIf { it != "left" || leftEditable }
                invalidate(); return pressed != null
            }
            MotionEvent.ACTION_MOVE -> if (pressed != null && hits.at(e.x, e.y) != pressed) { pressed = null; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null; invalidate()
                if (p != null && hits.at(e.x, e.y) == p) {
                    onFeedback?.invoke()
                    if (p == "left") onLeft?.invoke() else p.removePrefix("r").toIntOrNull()?.let { onRight?.invoke(it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
