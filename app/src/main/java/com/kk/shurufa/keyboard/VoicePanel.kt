package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.min

/**
 * 语音输入. One big round button (tap to start, tap again to finish) with rings that follow the
 * voice, one line under it (the sentence being heard, or what is going on), and the controls in
 * the corners: language and recognizer top left, 转文字 / 翻译 top right, 取消 bottom left,
 * 撤回上一句 and delete bottom right.
 */
@SuppressLint("ViewConstructor")
class VoicePanel(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onMic: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onLanguage: (() -> Unit)? = null
    var onMode: (() -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    /** 选模型: (engine, name) pairs, the current one, and what happens on a pick. */
    var engines: List<PickList.Item>
        get() = pick.items
        set(v) { pick.items = v }
    var engine: String
        get() = pick.current
        set(v) { pick.current = v }
    private val pick = PickList(this).apply { title = "识别用" }
    var engineName = ""
        set(v) { field = v; invalidate() }
    var onEngine: ((String) -> Unit)? = null
    private var picking = false
    /** 删除 (held: repeats; the flag says whether this is a repeat). */
    var onDelete: ((Boolean) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    enum class Look { READY, LOADING, LISTENING, WORKING, NEED_MODEL }
    var look = Look.READY
        set(v) {
            if (field != v) lit.animateTo(if (v == Look.LISTENING) 1f else 0f, dur(Motion.SWAP + 60))
            if (field != v && (v == Look.WORKING || v == Look.LOADING)) workSince = Motion.now()
            field = v
            if (v != Look.LISTENING) level.animateTo(0f, dur(200))
            invalidate()
        }
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

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ellipsizer = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val level = Tween(0f)
    /** 0 idle, 1 listening: the button fills with the accent. */
    private val lit = Tween(0f)
    private val spin = Tween(0f)
    /** When the wait for the recognizer began. */
    private var workSince = 0L
    private val hits = Hits()
    private var pressed: String? = null
    private val arc = RectF()
    private val chipRect = RectF()

    /** The voice level, 0..1, about thirty times a second while listening. */
    fun setLevel(v: Float) { level.animateTo(v, if (Motion.on(prefs)) 90 else 0); invalidate() }

    private val handler = Handler(Looper.getMainLooper())
    private var repeats = 0
    private val repeat = object : Runnable {
        override fun run() {
            if (pressed != "delete" || !isAttachedToWindow) return
            onDelete?.invoke(true); repeats++
            handler.postDelayed(this, if (repeats < 8) 80 else 45)
        }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacks(repeat); pressed = null }

    private val wave = android.graphics.Path()
    /** The voice, slowed: the waves swell with a phrase and settle in a pause, never twitch. */
    private var swell = 0f
    private var lastWave = 0L

    /**
     * 听的样子: three soft layers of water around the button. Each is a circle whose edge is moved
     * by a few slow sine waves going round at their own pace, so the outline never repeats; the
     * voice lifts the waves and the whole shape, the outer layers later and fainter, like ripples
     * on a cup of tea. Silent, it breathes, barely.
     */
    private fun drawWaves(c: Canvas, cx: Float, cy: Float, r: Float, l: Float) {
        val now = Motion.now()
        val dt = if (lastWave == 0L) 16f else (now - lastWave).coerceIn(1, 64).toFloat()
        lastWave = now
        // rises quickly, falls slowly
        val target = level.value
        val k = if (target > swell) 1f - kotlin.math.exp(-dt / 70f) else 1f - kotlin.math.exp(-dt / 320f)
        swell += (target - swell) * k
        val sec = now / 1000f
        val breath = 0.5f + 0.5f * kotlin.math.sin(sec * 2.1f)
        val layers = 3
        for (i in layers - 1 downTo 0) {
            val f = i / (layers - 1f)                     // 0 inner … 1 outer
            val v = swell * (1f - 0.35f * f)
            val base = r * (1.06f + 0.16f * f + (0.22f + 0.2f * f) * v + 0.02f * breath)
            val amp = r * (0.012f + (0.05f + 0.04f * f) * v)
            wave.reset()
            val n = 96
            for (j in 0..n) {
                val a = (j % n) * (2 * Math.PI.toFloat() / n)
                val d = kotlin.math.sin(3 * a + sec * (1.3f + 0.4f * i)) * 0.55f +
                    kotlin.math.sin(5 * a - sec * (1.9f - 0.3f * i) + i) * 0.3f +
                    kotlin.math.sin(8 * a + sec * 2.7f + 2f * i) * 0.15f * v
                val rr = base + amp * d
                val x = cx + rr * kotlin.math.cos(a); val y = cy + rr * kotlin.math.sin(a)
                if (j == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            wave.close()
            bg.color = Colors.fade(theme.accentSoft, l * (1f - 0.42f * f))
            c.drawPath(wave, bg)
        }
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        val m = dp(12f)
        val ch = dp(Ui.CHIP_H)
        val busy = look == Look.LISTENING || look == Look.WORKING || look == Look.LOADING
        // corners first: the middle takes the room they leave
        var x = m
        x = chip(c, "lang", language, ChipStyle.SOFT, x, m, icon = "language") + dp(Ui.CHIP_GAP)
        chip(c, "engine", engineName, if (picking) ChipStyle.ACTIVE else ChipStyle.SOFT, x, m, chevron = true)
        chip(c, "mode", mode, if (translating) ChipStyle.ACTIVE else ChipStyle.SOFT, width - m, m, alignRight = true, icon = if (translating) "translate" else null)
        val bottomY = height - m - ch
        // waiting on the recognizer: 取消 is the way out, so it stands out
        val waiting = look == Look.WORKING || look == Look.LOADING
        if (busy) chip(c, "cancel", "取消", if (waiting) ChipStyle.SOFT else ChipStyle.GHOST, m, bottomY, icon = if (waiting) "stop" else null)
        val del = chip(c, "delete", "", ChipStyle.SOFT, width - m, bottomY, alignRight = true, icon = "backspace")
        if (canUndo) chip(c, "undo", "撤回上一句", ChipStyle.SOFT, del - dp(Ui.CHIP_GAP), bottomY, alignRight = true, icon = "undo")

        // the button, and the line under it
        val lineY = bottomY - dp(16f)
        val top = m + ch
        val cy = (top + lineY - dp(12f)) / 2
        val room = min(cy - top - dp(4f), lineY - dp(12f) - cy)
        val r = (room / 1.38f).coerceIn(dp(20f), min(width, height) * 0.17f)
        val cx = width / 2f
        var again = lit.running
        // the waves and the level follow the microphone, which reports about 31 times a second:
        // that many frames show all there is, for as long as it listens
        var slow = level.running
        val l = lit.value
        if (l > 0.01f) {
            drawWaves(c, cx, cy, r, l)
            slow = true
        }
        val idle = if (pressed == "mic") t.softPressed else t.soft
        val on = if (pressed == "mic") t.accentPressed else t.accent
        bg.color = Colors.blend(idle, on, l)
        c.drawCircle(cx, cy, r, bg)
        hits.add("mic", cx - r, cy - r, cx + r, cy + r, slop = dp(12f))
        if (look == Look.WORKING || look == Look.LOADING) {
            if (!spin.running) { spin.set(0f); spin.animateTo(1f, 1000, Motion.LINEAR) }
            again = true
            // a sweep that grows and shrinks as it turns, with a faint track under it
            ip.style = Paint.Style.STROKE; ip.strokeWidth = dp(3.5f); ip.strokeCap = Paint.Cap.ROUND
            arc.set(cx - r - dp(7f), cy - r - dp(7f), cx + r + dp(7f), cy + r + dp(7f))
            ip.color = t.accentSoft; c.drawArc(arc, 0f, 360f, false, ip)
            val s = spin.value
            val sweep = 40f + 140f * kotlin.math.sin(Math.PI.toFloat() * s)
            ip.color = t.accent
            c.drawArc(arc, s * 360f - 90f + (Motion.now() % 3000) / 3000f * 360f, sweep, false, ip)
            ip.style = Paint.Style.FILL
        }
        ip.color = Colors.blend(t.ink, t.onAccent, l)
        Icons.draw(c, "mic", cx, cy, r * 0.9f, ip)
        // the sentence being heard, or else what is going on
        if (partial.isNotEmpty()) {
            tp.typeface = Ui.regular; tp.textSize = sp(Ui.TITLE); tp.color = if (translating) t.accent else t.ink
            val avail = width - dp(40f)
            val shown = if (tp.measureText(partial) <= avail) partial else {
                ellipsizer.set(tp)
                "…" + TextUtils.ellipsize(partial.reversed(), ellipsizer, avail - tp.measureText("…"), TextUtils.TruncateAt.END).toString().reversed().trimEnd('…')
            }
            drawText(c, shown, cx, lineY, tp)
        } else if (waiting) {
            // what it is waiting for, for how long, and how to stop waiting
            val secs = (Motion.now() - workSince) / 1000f
            val dots = ".".repeat(1 + ((Motion.now() - workSince) / 380 % 3).toInt())
            tp.typeface = Ui.medium; tp.textSize = sp(Ui.BODY); tp.color = t.accent
            val head = status.trimEnd('…', '.') + dots + if (secs >= 1f) String.format("  %.1f 秒", secs) else ""
            drawText(c, head, cx, lineY - if (secs >= 2f) dp(9f) else 0f, tp)
            if (secs >= 2f) {
                tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL); tp.color = t.hint
                drawText(c, "点「取消」可以打断", cx, lineY + dp(10f), tp)
            }
            tp.typeface = Ui.regular
        } else {
            tp.typeface = Ui.regular; tp.textSize = sp(Ui.BODY); tp.color = t.inkSoft
            drawText(c, status, cx, lineY, tp)
        }
        if (picking) {
            // the list of recognizers, over the middle of the panel
            val pad = dp(12f)
            if (pick.draw(c, chipRect.apply { set(pad, m + ch + dp(8f), width - pad, height - pad) }, t, Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)))) again = true
        }
        if (again) postInvalidateOnAnimation() else if (slow) invalidateIn(LISTEN_FRAME_MS)
    }

    /** Draws a chip anchored at its left (or right) edge [x]; returns the opposite edge. */
    private fun chip(c: Canvas, id: String, label: String, style: ChipStyle, x: Float, y: Float,
                     alignRight: Boolean = false, icon: String? = null, chevron: Boolean = false): Float {
        val w = chipWidth(label, icon, chevron)
        val r = chipRect.apply { if (alignRight) set(x - w, y, x, y + dp(Ui.CHIP_H)) else set(x, y, x + w, y + dp(Ui.CHIP_H)) }
        drawChip(c, r, label, style, pressed = pressed == id, icon = icon, chevron = chevron)
        hits.add(id, r, slop = dp(5f))
        return if (alignRight) r.left else r.right
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (picking && (pick.busy || (e.actionMasked == MotionEvent.ACTION_DOWN && pick.contains(e.x, e.y)))) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFeedback?.invoke()
            pick.onTouch(e)?.let { id -> picking = false; onEngine?.invoke(id) }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                if (pressed == "delete") { onDelete?.invoke(false); repeats = 0; handler.postDelayed(repeat, 420) }
                else if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeat)
                val p = pressed
                when {
                    // while the list is open, anything but a choice (or the chip) just closes it
                    p == "engine" -> { picking = !picking; if (picking) pick.reveal() }
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

    private companion object {
        /** A frame while listening: about 30 a second, as often as the microphone reports. */
        const val LISTEN_FRAME_MS = 33L
    }
}
