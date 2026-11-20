package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs
import kotlin.math.min

/**
 * 语音输入: one big round button (tap to start, tap again to finish), a ring that follows the
 * voice level, a status line, the language being recognized (tap to change) and 取消.
 */
@SuppressLint("ViewConstructor")
class VoicePanel(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onMic: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLanguage: (() -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    enum class Look { READY, LISTENING, WORKING, NEED_MODEL }
    var look = Look.READY
        set(v) { field = v; if (v != Look.LISTENING) level.animateTo(0f, 200); invalidate() }
    var status = ""
        set(v) { field = v; invalidate() }
    var partial = ""
        set(v) { field = v; invalidate() }
    var language = "中文"
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val level = Tween(0f)
    private val spin = Tween(0f)
    private var mic = RectF()
    private var langR = RectF()
    private var cancelR = RectF()
    private var pressed = 0   // 1 mic, 2 language, 3 cancel

    fun setLevel(v: Float) { level.animateTo(v, 90); invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val cx = width / 2f
        val cy = height * 0.5f
        val r = min(width, height) * 0.2f
        mic = RectF(cx - r, cy - r, cx + r, cy + r)
        var again = level.running
        // the ring grows with the voice
        if (look == Look.LISTENING) {
            val lv = level.value
            bg.color = theme.accentSoft
            c.drawCircle(cx, cy, r * (1.18f + 0.45f * lv), bg)
            bg.color = theme.accentSoft
            c.drawCircle(cx, cy, r * (1.08f + 0.2f * lv), bg)
        }
        bg.color = when (look) {
            Look.LISTENING -> theme.accent
            Look.WORKING -> theme.soft
            else -> if (pressed == 1) theme.softPressed else theme.soft
        }
        c.drawCircle(cx, cy, r, bg)
        if (look == Look.WORKING) {
            // a short arc going round while recognizing
            if (!spin.running) { spin.set(0f); spin.animateTo(1f, 900, android.view.animation.LinearInterpolator()) }
            again = true
            ip.color = theme.accent; ip.style = Paint.Style.STROKE; ip.strokeWidth = dp(3f); ip.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(cx - r - dp(6f), cy - r - dp(6f), cx + r + dp(6f), cy + r + dp(6f)), spin.value * 360f - 90f, 70f, false, ip)
            ip.style = Paint.Style.FILL
        }
        ip.color = if (look == Look.LISTENING) theme.onAccent else theme.ink
        Icons.draw(c, "mic", cx, cy, r * 0.95f, ip)
        // status above, what is being heard below
        tp.typeface = Ui.medium; tp.textSize = sp(Ui.BODY); tp.color = theme.inkSoft
        text(c, status, cx, cy - r - dp(26f))
        tp.typeface = Ui.regular
        if (partial.isNotEmpty()) {
            tp.textSize = sp(Ui.BODY); tp.color = theme.ink
            var t = partial
            while (t.length > 1 && tp.measureText(t) > width - dp(40f)) t = t.drop(1)
            text(c, if (t.length < partial.length) "…$t" else t, cx, cy + r + dp(28f))
        }
        // language (left) and cancel (right), as soft pills at the button's height
        val ph = dp(36f)
        tp.textSize = sp(Ui.SMALL)
        val lw = tp.measureText(language) + dp(36f)
        langR = RectF(dp(14f), cy - ph / 2, dp(14f) + lw, cy + ph / 2)
        bg.color = if (pressed == 2) theme.softPressed else theme.soft
        c.drawRoundRect(langR, ph / 2, ph / 2, bg)
        ip.color = theme.inkSoft
        Icons.draw(c, "language", langR.left + dp(15f), cy, dp(15f), ip)
        tp.color = theme.inkSoft; tp.textAlign = Paint.Align.LEFT
        text(c, language, langR.left + dp(27f), cy)
        tp.textAlign = Paint.Align.CENTER
        cancelR = RectF()
        if (look == Look.LISTENING || look == Look.WORKING) {
            val cw = dp(64f)
            cancelR = RectF(width - dp(14f) - cw, cy - ph / 2, width - dp(14f), cy + ph / 2)
            bg.color = if (pressed == 3) theme.softPressed else theme.soft
            c.drawRoundRect(cancelR, ph / 2, ph / 2, bg)
            tp.color = theme.inkSoft
            text(c, "取消", cancelR.centerX(), cy)
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val big = RectF(mic).apply { inset(-dp(12f), -dp(12f)) }
                pressed = when {
                    big.contains(e.x, e.y) -> 1
                    langR.contains(e.x, e.y) -> 2
                    cancelR.contains(e.x, e.y) -> 3
                    else -> 0
                }
                if (pressed != 0) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (pressed) { 1 -> onMic?.invoke(); 2 -> onLanguage?.invoke(); 3 -> onCancel?.invoke() }
                pressed = 0; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }
}
