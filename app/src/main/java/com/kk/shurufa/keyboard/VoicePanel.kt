package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs
import kotlin.math.min

/**
 * 语音输入. One big round button (tap to start, tap again to finish), a ring that follows the
 * voice level, the sentence being heard under it, and four small controls: the language
 * (top left), 转文字 / 翻译 (top right), 取消 and 撤回上一句 (bottom).
 */
@SuppressLint("ViewConstructor")
class VoicePanel(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onMic: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLanguage: (() -> Unit)? = null
    var onMode: (() -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    /** 选模型: (engine, name) pairs, the current one, and what happens on a pick. */
    var engines: List<Pair<String, String>> = emptyList()
    var engine = ""
    var engineName = ""
        set(v) { field = v; invalidate() }
    var onEngine: ((String) -> Unit)? = null
    private var picking = false
    /** 删除 (held: repeats; the flag says whether this is a repeat). */
    var onDelete: ((Boolean) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    enum class Look { READY, LOADING, LISTENING, WORKING, NEED_MODEL }
    var look = Look.READY
        set(v) { field = v; if (v != Look.LISTENING) level.animateTo(0f, 200); invalidate() }
    var status = ""
        set(v) { field = v; invalidate() }
    var partial = ""
        set(v) { field = v; invalidate() }
    var language = "中文"
        set(v) { field = v; invalidate() }
    var mode = "转文字"
        set(v) { field = v; invalidate() }
    var translating = false
        set(v) { field = v; invalidate() }
    var canUndo = false
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val level = Tween(0f)
    private val spin = Tween(0f)
    private val hits = mutableListOf<Pair<RectF, String>>()
    private var pressed: String? = null
    private val arc = RectF()

    fun setLevel(v: Float) { level.animateTo(v, 90); invalidate() }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeats = 0
    private val repeat = object : Runnable {
        override fun run() {
            if (pressed != "delete" || !isAttachedToWindow) return
            onDelete?.invoke(true); repeats++
            handler.postDelayed(this, if (repeats < 8) 80 else 45)
        }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacks(repeat); pressed = null }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val cx = width / 2f
        // three bands: status under the top chips, the sentence above the bottom chips, the
        // button centered between them and sized to the room left
        val statusY = dp(62f)
        val partialY = height - dp(64f)
        val cy = (statusY + partialY) / 2
        val r = ((partialY - statusY) / 2 - dp(14f)).coerceIn(dp(18f), min(width, height) * 0.16f)
        var again = level.running
        if (look == Look.LISTENING) {
            val lv = level.value
            bg.color = theme.accentSoft
            c.drawCircle(cx, cy, r * (1.15f + 0.35f * lv), bg)
            c.drawCircle(cx, cy, r * (1.06f + 0.16f * lv), bg)
        }
        bg.color = when (look) {
            Look.LISTENING -> if (pressed == "mic") theme.accentPressed else theme.accent
            else -> if (pressed == "mic") theme.softPressed else theme.soft
        }
        c.drawCircle(cx, cy, r, bg)
        hits += RectF(cx - r - dp(12f), cy - r - dp(12f), cx + r + dp(12f), cy + r + dp(12f)) to "mic"
        if (look == Look.WORKING || look == Look.LOADING) {
            if (!spin.running) { spin.set(0f); spin.animateTo(1f, 900, android.view.animation.LinearInterpolator()) }
            again = true
            ip.color = theme.accent; ip.style = Paint.Style.STROKE; ip.strokeWidth = dp(3f); ip.strokeCap = Paint.Cap.ROUND
            arc.set(cx - r - dp(6f), cy - r - dp(6f), cx + r + dp(6f), cy + r + dp(6f))
            c.drawArc(arc, spin.value * 360f - 90f, 70f, false, ip)
            ip.style = Paint.Style.FILL
        }
        ip.color = if (look == Look.LISTENING) theme.onAccent else theme.ink
        Icons.draw(c, "mic", cx, cy, r * 0.9f, ip)
        // status above the button, the sentence being heard below it
        tp.typeface = Ui.medium; tp.textSize = sp(Ui.BODY); tp.color = theme.inkSoft
        text(c, status, cx, statusY)
        tp.typeface = Ui.regular
        if (partial.isNotEmpty()) {
            tp.textSize = sp(Ui.BODY + 1f); tp.color = if (translating) theme.accent else theme.ink
            val avail = width - dp(40f)
            val shown = if (tp.measureText(partial) <= avail) partial
                else "…" + TextUtils.ellipsize(partial.reversed(), TextPaint(tp), avail - tp.measureText("…"), TextUtils.TruncateAt.END).toString().reversed().trimEnd('…')
            text(c, shown, cx, partialY)
        }
        // corners
        val ph = dp(34f); val m = dp(12f)
        val lr = chip(c, "lang", language, "language", m, m, ph, left = true)
        chip(c, "engine", "$engineName ▾", null, lr.right + dp(8f), m, ph, left = true, on = picking)
        chip(c, "mode", mode, if (translating) "translate" else null, width - m, m, ph, left = false, on = translating)
        if (look == Look.LISTENING || look == Look.WORKING || look == Look.LOADING) chip(c, "cancel", "取消", null, m, height - m - ph, ph, left = true)
        // bottom right: delete, and taking back the last sentence beside it
        val del = chip(c, "delete", "", "backspace", width - m, height - m - ph, ph, left = false, iconOnly = true)
        if (canUndo) chip(c, "undo", "撤回上一句", "undo", del.left - dp(8f), height - m - ph, ph, left = false)
        if (picking) drawPicker(c, m + ph + dp(8f))
        if (again) postInvalidateOnAnimation()
    }

    /** The list of recognizers, over the middle of the panel. */
    private fun drawPicker(c: Canvas, top: Float) {
        val pad = dp(12f)
        val card = RectF(pad, top, width - pad, height - pad)
        bg.color = theme.surface
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        c.drawRoundRect(card, rad, rad, bg)
        tp.textSize = sp(Ui.CAPTION); tp.color = theme.hint; tp.textAlign = Paint.Align.LEFT
        c.drawText("识别用", card.left + dp(12f), card.top + dp(18f), tp)
        tp.textAlign = Paint.Align.CENTER
        val h = dp(32f); val gap = dp(6f)
        var x = card.left + dp(12f); var y = card.top + dp(26f)
        for ((id, name) in engines) {
            tp.textSize = sp(Ui.SMALL)
            val w = tp.measureText(name) + dp(24f)
            if (x + w > card.right - dp(12f)) { x = card.left + dp(12f); y += h + gap }
            if (y + h > card.bottom - dp(8f)) break
            val r = RectF(x, y, x + w, y + h)
            val on = id == engine
            bg.color = if (on) theme.accentSoft else if (pressed == "eng:$id") theme.softPressed else theme.soft
            c.drawRoundRect(r, h / 2, h / 2, bg)
            tp.color = if (on) theme.accent else theme.ink; tp.typeface = if (on) Ui.medium else Ui.regular
            text(c, name, r.centerX(), r.centerY())
            tp.typeface = Ui.regular
            hits += r to "eng:$id"
            x += w + gap
        }
    }

    private fun chip(c: Canvas, id: String, label: String, icon: String?, x: Float, y: Float, h: Float, left: Boolean, on: Boolean = false, iconOnly: Boolean = false): RectF {
        tp.textSize = sp(Ui.SMALL)
        val w = if (iconOnly) dp(52f) else tp.measureText(label) + dp(if (icon != null) 40f else 26f)
        val r = if (left) RectF(x, y, x + w, y + h) else RectF(x - w, y, x, y + h)
        bg.color = when {
            on -> theme.accentSoft
            pressed == id -> theme.softPressed
            else -> theme.soft
        }
        c.drawRoundRect(r, h / 2, h / 2, bg)
        val ink = if (on) theme.accent else theme.inkSoft
        var tx = r.centerX()
        if (iconOnly && icon != null) {
            ip.color = ink; Icons.draw(c, icon, r.centerX(), r.centerY(), dp(19f), ip)
            hits += RectF(r).apply { inset(-dp(4f), -dp(6f)) } to id
            return r
        }
        if (icon != null) {
            ip.color = ink
            Icons.draw(c, icon, r.left + dp(17f), r.centerY(), dp(15f), ip)
            tx = r.left + dp(28f) + tp.measureText(label) / 2
        }
        tp.color = ink
        text(c, label, tx, r.centerY())
        hits += RectF(r).apply { inset(-dp(4f), -dp(6f)) } to id
        return r
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second
                if (pressed == "delete") { onDelete?.invoke(false); repeats = 0; handler.postDelayed(repeat, 420) }
                else if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeat)
                val p = pressed
                when {
                    // while the list is open, anything but a choice (or the chip) just closes it
                    p != null && p.startsWith("eng:") -> { picking = false; onEngine?.invoke(p.removePrefix("eng:")) }
                    p == "engine" -> picking = !picking
                    picking -> picking = false
                    p == "mic" -> onMic?.invoke()
                    p == "lang" -> onLanguage?.invoke()
                    p == "mode" -> onMode?.invoke()
                    p == "cancel" -> onCancel?.invoke()
                    p == "undo" -> onUndo?.invoke()
                }
                pressed = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeat); pressed = null; invalidate() }
        }
        return true
    }
}
