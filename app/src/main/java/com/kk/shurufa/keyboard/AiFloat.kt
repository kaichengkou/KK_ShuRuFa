package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.max
import kotlin.math.min

/**
 * AI 浮窗: what the AI offers (云联想's continuations, the AI 候选 for long pinyin) in a small card
 * floating at the keyboard's top right, over the app, instead of in among the candidates. It never
 * moves what is on the bar, so space and the first candidate stay where the hand expects them; a
 * tap takes a line, × puts it away until the AI says something new.
 */
@SuppressLint("ViewConstructor")
class AiFloat(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onPick: ((Int) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    private var title = ""
    private var items: List<String> = emptyList()
    /** What × put away: the same lines are not shown again. */
    private var dismissed: List<String> = emptyList()
    private val appear = Tween(0f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ellipsizer = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    private val hits = Hits()
    private var pressed: String? = null

    val showing get() = items.isNotEmpty()

    /** Shows [lines] under [title] (empty: hides). */
    fun show(title: String, lines: List<String>) {
        val list = if (lines == dismissed) emptyList() else lines
        if (list == items && title == this.title) return
        val was = items.isNotEmpty()
        this.title = title; items = list
        if (list.isEmpty()) { appear.set(0f); visibility = GONE; return }
        visibility = VISIBLE
        if (!was) { appear.set(0f); appear.animateTo(1f, dur(Motion.POP_IN), Motion.POP) }
        requestLayout(); invalidate()
    }

    private val pad get() = dp(12f)
    private val rowH get() = dp(40f)
    private val headH get() = dp(26f)
    private val maxW get() = min(dp(300f), resources.displayMetrics.widthPixels * 0.72f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        tp.typeface = Ui.regular; tp.textSize = sp(Ui.BODY + 1f)
        var w = dp(150f)
        for (s in items) w = max(w, tp.measureText(s) + pad * 2)
        val shadow = dp(10f)
        setMeasuredDimension((min(w, maxW) + shadow * 2).toInt(), (headH + rowH * items.size + dp(6f) + shadow * 2).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val t = theme
        val a = appear.value
        val s = dp(10f)
        hits.clear()
        c.save()
        // grows from the top right corner, where the eye goes first
        c.scale(0.92f + 0.08f * a, 0.92f + 0.08f * a, width - s, height - s)
        card.set(s, s, width - s, height - s)
        bg.color = Colors.fade(t.popup, a)
        bg.setShadowLayer(dp(10f), 0f, dp(3f), Colors.alpha(t.shadow or (0xFF shl 24), ((if (t.dark) 0x70 else 0x36) * a).toInt()))
        c.drawRoundRect(card, dp(14f), dp(14f), bg)
        bg.clearShadowLayer()
        // the head: what this is, and ×
        tp.typeface = Ui.medium; tp.textSize = sp(Ui.CAPTION); tp.color = Colors.fade(t.accent, a); tp.textAlign = Paint.Align.LEFT
        drawText(c, "✦ $title", card.left + pad, card.top + headH * 0.62f, tp)
        val xr = card.right - pad
        tp.textAlign = Paint.Align.CENTER; tp.color = Colors.fade(t.hint, a); tp.typeface = Ui.regular; tp.textSize = sp(Ui.BODY)
        drawText(c, "×", xr - dp(4f), card.top + headH * 0.6f, tp)
        hits.add("close", xr - dp(22f), card.top, card.right, card.top + headH + dp(4f), slop = dp(6f))
        // the lines
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.BODY + 1f); tp.typeface = Ui.regular
        ellipsizer.set(tp)
        var y = card.top + headH
        for ((i, line) in items.withIndex()) {
            val r = RectF(card.left + dp(4f), y, card.right - dp(4f), y + rowH)
            if (pressed == "i$i") { bg.color = Colors.fade(t.softPressed, a); c.drawRoundRect(r, dp(10f), dp(10f), bg) }
            if (i > 0) { bg.color = Colors.fade(t.divider, a); c.drawRect(card.left + pad, y, card.right - pad, y + dp(0.7f), bg) }
            tp.color = Colors.fade(t.ink, a)
            val shown = TextUtils.ellipsize(line, ellipsizer, card.width() - pad * 2, TextUtils.TruncateAt.END)
            drawText(c, shown, card.left + pad, y + rowH / 2, tp)
            hits.add("i$i", r)
            y += rowH
        }
        c.restore()
        if (appear.running) invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hits.at(e.x, e.y); invalidate(); return pressed != null }
            MotionEvent.ACTION_MOVE -> if (pressed != null && hits.at(e.x, e.y) != pressed) { pressed = null; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null; invalidate()
                if (p != null && hits.at(e.x, e.y) == p) {
                    onFeedback?.invoke()
                    if (p == "close") { dismissed = items; show(title, emptyList()) }
                    else p.removePrefix("i").toIntOrNull()?.let { onPick?.invoke(it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
