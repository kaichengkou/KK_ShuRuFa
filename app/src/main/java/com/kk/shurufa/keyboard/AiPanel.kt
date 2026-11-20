package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs

/**
 * AI 改写: what will be sent (the selection or the whole field) on top, the actions as soft
 * chips, and the result in a card with 替换 / 插入. While a request runs the chosen chip pulses.
 */
@SuppressLint("ViewConstructor")
class AiPanel(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var actions: List<Pair<String, String>> = emptyList()
        set(v) { field = v; invalidate() }
    var onAction: ((String) -> Unit)? = null
    var onApply: ((replace: Boolean) -> Unit)? = null
    var onSetup: (() -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    var source = ""
        set(v) { field = v; invalidate() }
    var sourceLabel = ""
        set(v) { field = v; invalidate() }
    var result = ""
        set(v) { field = v; resultLayout = null; invalidate() }
    var status = ""
        set(v) { field = v; invalidate() }
    var busy: String? = null
        set(v) { field = v; invalidate() }
    var needsSetup = false
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var resultLayout: StaticLayout? = null
    private val hits = mutableListOf<Pair<RectF, String>>()
    private var pressed: String? = null
    private val pulse = Tween(0f)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val pad = dp(10f)
        val rad = dp(prefs.keyRadiusDp)
        var y = pad
        // what goes out
        tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.medium; tp.textSize = sp(Ui.SMALL); tp.color = theme.hint
        c.drawText(sourceLabel, pad + dp(2f), y + tp.textSize, tp)
        tp.typeface = Ui.regular; tp.color = theme.inkSoft
        val src = source.replace('\n', ' ')
        val avail = width - 2 * pad - tp.measureText(sourceLabel) - dp(10f)
        c.drawText(TextUtils.ellipsize(src, TextPaint(tp), avail, TextUtils.TruncateAt.END).toString(), pad + tp.measureText(sourceLabel) + dp(10f), y + tp.textSize, tp)
        y += dp(24f)
        if (needsSetup) {
            val r = RectF(pad, y, width - pad, y + dp(48f))
            bg.color = if (pressed == "setup") theme.accentPressed else theme.accent
            c.drawRoundRect(r, rad, rad, bg)
            tp.color = theme.onAccent; tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY); tp.typeface = Ui.medium
            text(c, "填写 Claude API Key 后即可使用", r.centerX(), r.centerY())
            tp.typeface = Ui.regular
            hits += r to "setup"
            return
        }
        // action chips, wrapping
        tp.textSize = sp(Ui.BODY); tp.textAlign = Paint.Align.CENTER
        val ch = dp(36f); val gap = dp(6f)
        var x = pad
        if (pulse.running || busy != null) postInvalidateOnAnimation()
        if (busy != null && !pulse.running) { pulse.set(0f); pulse.animateTo(1f, 900) }
        for ((id, name) in actions) {
            val w = tp.measureText(name) + dp(24f)
            if (x + w > width - pad) { x = pad; y += ch + gap }
            val r = RectF(x, y, x + w, y + ch)
            val on = busy == id
            bg.color = when {
                on -> blend(theme.accentSoft, theme.accent, 0.25f + 0.25f * kotlin.math.sin(pulse.value * Math.PI).toFloat())
                pressed == id -> theme.softPressed
                else -> theme.soft
            }
            c.drawRoundRect(r, ch / 2, ch / 2, bg)
            tp.color = if (on) theme.accent else theme.ink
            text(c, name, r.centerX(), r.centerY())
            hits += r to id
            x += w + gap
        }
        y += ch + dp(10f)
        // result card
        val card = RectF(pad, y, width - pad, height - pad)
        if (card.height() < dp(40f)) return
        bg.color = theme.surface
        c.drawRoundRect(card, Ui.radiusLarge(rad, dp(1f)), Ui.radiusLarge(rad, dp(1f)), bg)
        if (result.isEmpty()) {
            tp.color = theme.hint; tp.textSize = sp(Ui.SMALL)
            text(c, status.ifEmpty { "选一个操作，结果会显示在这里" }, card.centerX(), card.centerY())
            return
        }
        val btnW = dp(64f); val btnH = dp(34f)
        val inner = dp(12f)
        val lay = resultLayout ?: run {
            body.textSize = sp(Ui.BODY + 1f); body.color = theme.ink
            val w = (card.width() - 2 * inner).toInt().coerceAtLeast(1)
            val lines = ((card.height() - 2 * inner - btnH - dp(6f)) / (body.fontSpacing * 1.1f)).toInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(result, 0, result.length, body, w).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
                .also { resultLayout = it }
        }
        c.save(); c.translate(card.left + inner, card.top + inner); lay.draw(c); c.restore()
        val by = card.bottom - inner - btnH
        val rep = RectF(card.right - inner - btnW, by, card.right - inner, by + btnH)
        val ins = RectF(rep.left - dp(8f) - btnW, by, rep.left - dp(8f), by + btnH)
        bg.color = if (pressed == "replace") theme.accentPressed else theme.accent
        c.drawRoundRect(rep, btnH / 2, btnH / 2, bg)
        bg.color = if (pressed == "insert") theme.softPressed else theme.soft
        c.drawRoundRect(ins, btnH / 2, btnH / 2, bg)
        tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium
        tp.color = theme.onAccent; text(c, "替换", rep.centerX(), rep.centerY())
        tp.color = theme.ink; text(c, "插入", ins.centerX(), ins.centerY())
        tp.typeface = Ui.regular
        hits += rep to "replace"; hits += ins to "insert"
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second
                if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (val p = pressed) {
                    null -> {}
                    "setup" -> onSetup?.invoke()
                    "replace" -> onApply?.invoke(true)
                    "insert" -> onApply?.invoke(false)
                    else -> if (busy == null) onAction?.invoke(p)
                }
                pressed = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
