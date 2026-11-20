package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Clipboard cards stacked like the recent-apps switcher. Swipe sideways to browse, tap the
 * front card to paste, swipe it up to remove it, long-press to add it to or take it out of
 * the favorites.
 */
@SuppressLint("ViewConstructor")
class CardStackView(context: Context, private val prefs: Prefs) : View(context) {
    data class Card(val text: String, val tag: String, val starred: Boolean)

    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var cards: List<Card> = emptyList()
        private set
    var emptyText = ""
    var onTap: ((Int) -> Unit)? = null
    var onLong: ((Int) -> Unit)? = null
    var onDismiss: ((Int) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dur(ms: Long) = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val layouts = HashMap<Int, StaticLayout>()

    /** Fractional index of the card at the front. */
    private val pos = Tween(0f)
    private var dragPos = 0f
    private val lift = Tween(0f)
    private val handler = Handler(Looper.getMainLooper())

    fun setCards(list: List<Card>, keepPosition: Boolean) {
        cards = list
        layouts.clear()
        val p = if (keepPosition) pos.value.coerceIn(0f, max(0f, list.size - 1f)) else 0f
        pos.set(p.roundToInt().toFloat())
        lift.set(0f)
        invalidate()
    }

    private val cardW get() = min(width * 0.78f, dp(420f))
    private val cardH get() = height - dp(20f)
    private val spacing get() = cardW * 0.56f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layouts.clear()

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        if (cards.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.color = theme.hint; tp.textSize = sp(13.5f)
            val fm = tp.fontMetrics
            c.drawText(emptyText, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, tp)
            return
        }
        val p = if (dragging) dragPos else pos.value
        if (pos.running || lift.running) postInvalidateOnAnimation()
        val front = p.roundToInt().coerceIn(0, cards.size - 1)
        // back to front: the further a card is from the middle, the earlier it is drawn
        val order = cards.indices.filter { abs(it - p) < 3.2f }.sortedByDescending { abs(it - p) }
        for (i in order) {
            val d = i - p
            val scale = (1f - 0.09f * abs(d)).coerceAtLeast(0.7f)
            val cx = width / 2f + d * spacing * (1f - 0.12f * min(abs(d), 2f))
            val w = cardW * scale; val h = cardH * scale
            var top = (height - h) / 2f
            var alpha = (1f - 0.28f * abs(d)).coerceIn(0f, 1f)
            if (i == front && !dragging) { top -= lift.value * height; alpha *= (1f - lift.value * 1.4f).coerceAtLeast(0f) }
            drawCard(c, i, RectF(cx - w / 2, top, cx + w / 2, top + h), alpha, abs(d) < 0.5f)
        }
        // position indicator
        tp.textAlign = Paint.Align.CENTER; tp.color = theme.hint; tp.textSize = sp(10.5f)
        c.drawText("${front + 1} / ${cards.size}", width / 2f, height - dp(4f), tp)
    }

    private fun drawCard(c: Canvas, i: Int, r: RectF, alpha: Float, isFront: Boolean) {
        val card = cards[i]
        val rad = dp(18f)
        bg.color = theme.shadow
        bg.alpha = (bg.alpha * alpha).toInt()
        c.drawRoundRect(r.left, r.top + dp(3f), r.right, r.bottom + dp(3f), rad, rad, bg)
        bg.color = theme.surface
        bg.alpha = (255 * alpha).toInt()
        c.drawRoundRect(r, rad, rad, bg)
        val pad = dp(16f) * (r.width() / cardW)
        var y = r.top + pad
        val label = listOfNotNull(if (card.starred) "★ 收藏" else null, card.tag.takeIf { it.isNotEmpty() }).joinToString(" · ")
        if (label.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT; tp.color = theme.accent; tp.alpha = (255 * alpha).toInt()
            tp.textSize = sp(11f) * (r.width() / cardW); tp.isFakeBoldText = true
            c.drawText(label, r.left + pad, y + tp.textSize, tp)
            tp.isFakeBoldText = false
            y += tp.textSize * 1.8f
        }
        val lay = layouts.getOrPut(i) {
            body.textSize = sp(15f)
            body.color = theme.ink
            val w = (cardW - 2 * dp(16f)).toInt().coerceAtLeast(1)
            val lineH = body.fontSpacing * 1.1f
            val lines = ((cardH - dp(16f) * 2 - sp(20f)) / lineH).toInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(card.text, 0, card.text.length, body, w)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
        }
        c.save()
        val s = r.width() / cardW
        c.translate(r.left + pad, y)
        c.scale(s, s)
        lay.paint.alpha = (255 * alpha).toInt()
        lay.draw(c)
        c.restore()
        if (isFront && i == 0 && cards.size > 1 && !dragging) {
            tp.textAlign = Paint.Align.RIGHT; tp.color = theme.hint; tp.alpha = (200 * alpha).toInt(); tp.textSize = sp(10f)
            c.drawText("点按粘贴 · 上滑删除 · 长按收藏", r.right - pad, r.bottom - pad * 0.6f, tp)
        }
    }

    // ---- touch ----
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var lifting = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var longFired = false
    private val longRunnable = Runnable {
        if (!dragging && !lifting && cards.isNotEmpty()) {
            longFired = true
            onFeedback?.invoke("popup")
            onLong?.invoke(pos.value.roundToInt().coerceIn(0, cards.size - 1))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (cards.isEmpty()) return true
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false; lifting = false; longFired = false
                dragPos = pos.value
                onFeedback?.invoke("press")
                handler.postDelayed(longRunnable, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && !lifting && (abs(dx) > slop || abs(dy) > slop)) {
                    handler.removeCallbacks(longRunnable)
                    if (abs(dx) >= abs(dy)) dragging = true else if (dy < 0) lifting = true
                }
                if (dragging) {
                    dragPos = (pos.value - dx / spacing).coerceIn(-0.4f, cards.size - 0.6f)
                    invalidate()
                }
                if (lifting) { lift.set((-dy / height).coerceIn(0f, 1f)); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                vt?.computeCurrentVelocity(1000)
                when {
                    dragging -> {
                        val v = vt?.xVelocity ?: 0f
                        var target = dragPos.roundToInt()
                        if (abs(v) > dp(400f)) target = if (v < 0) dragPos.toInt() + 1 else dragPos.toInt()
                        target = target.coerceIn(0, cards.size - 1)
                        pos.set(dragPos)
                        pos.animateTo(target.toFloat(), dur(260), Tween.EASE_OUT)
                        dragging = false
                        if (target != dragPos.roundToInt()) onFeedback?.invoke("press")
                    }
                    lifting -> {
                        val vy = vt?.yVelocity ?: 0f
                        val i = pos.value.roundToInt().coerceIn(0, cards.size - 1)
                        if (lift.value > 0.3f || vy < -dp(900f)) {
                            lift.animateTo(1f, dur(160))
                            handler.postDelayed({ onDismiss?.invoke(i) }, dur(160))
                        } else lift.animateTo(0f, dur(180))
                        lifting = false
                    }
                    !longFired -> {
                        // a tap on a side card brings it to the front; on the front card it pastes
                        val front = pos.value.roundToInt()
                        val d = ((e.x - width / 2f) / spacing)
                        if (abs(d) > 0.55f) {
                            val target = (front + d.roundToInt()).coerceIn(0, cards.size - 1)
                            pos.animateTo(target.toFloat(), dur(260), Tween.EASE_OUT)
                        } else onTap?.invoke(front.coerceIn(0, cards.size - 1))
                    }
                }
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                dragging = false; lifting = false; lift.animateTo(0f, dur(150))
                vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }
}
