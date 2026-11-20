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
import android.view.View
import com.kk.shurufa.Prefs

/**
 * 文字编辑: a round direction pad with 选择 in its middle, 行首 · 全选 · 行尾 under it, and
 * 删除 / 复制 / 剪切 / 粘贴 down the right side. The pad's four sectors and the side keys use the
 * keyboard's own soft and solid fills. Arrows and 删除 repeat while held (sliding to another
 * sector keeps going in the new direction). 复制 and 剪切 are dimmed while nothing is selected.
 */
@SuppressLint("ViewConstructor")
class EditPanel(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onAction: ((String) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null
    var selecting = false
        set(v) { if (field != v) { field = v; selAnim.animateTo(if (v) 1f else 0f, dur(180)) }; invalidate() }
    var hasSelection = false
        set(v) { field = v; invalidate() }
    /** "icon", "both" or "text" (光标面板标签). */
    var labels = "both"

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dur(ms: Long) = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selAnim = Tween(0f)
    private val handler = Handler(Looper.getMainLooper())

    private enum class Kind { ARROW, SELECT, SOFT }
    private class Btn(val id: String, val icon: String, val text: String, val kind: Kind, val repeat: Boolean) {
        val r = RectF()
        val press = Tween(0f)
    }

    private val buttons = listOf(
        Btn("home", "home", "行首", Kind.SOFT, false), Btn("select_all", "select_all", "全选", Kind.SOFT, false), Btn("end", "end", "行尾", Kind.SOFT, false),
        Btn("backspace", "backspace", "删除", Kind.SOFT, true), Btn("copy", "copy", "复制", Kind.SOFT, false),
        Btn("cut", "cut", "剪切", Kind.SOFT, false), Btn("paste", "paste", "粘贴", Kind.SOFT, false),
    )
    // the dial: its four sectors and the center are buttons too, hit by angle and distance
    private val arrows = mapOf(Dir.UP to Btn("up", "up", "", Kind.ARROW, true), Dir.DOWN to Btn("down", "down", "", Kind.ARROW, true),
        Dir.LEFT to Btn("left", "left", "", Kind.ARROW, true), Dir.RIGHT to Btn("right", "right", "", Kind.ARROW, true))
    private val center = Btn("select", "cursor", "选择", Kind.SELECT, false)
    private var dcx = 0f
    private var dcy = 0f
    private var dr = 0f
    private val innerR get() = dr * 0.4f
    private var pressed: Btn? = null
    private val tmp = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutButtons()

    private fun layoutButtons() {
        // the keyboard's own margins, so the edges line up with the letters
        val side = dp(4f); val top = dp(6f); val bottom = height - dp(prefs.bottomPaddingDp + 2f)
        val g = dp(prefs.keyGapDp)
        val colW = (width - 2 * side - g) * 0.24f
        val leftW = width - 2 * side - g - colW
        val stripH = ((bottom - top) * 0.25f).coerceIn(dp(38f), dp(52f))
        val sw = (leftW - 2 * g) / 3
        for (i in 0 until 3) {
            val x = side + i * (sw + g)
            buttons[i].r.set(x, bottom - stripH, x + sw, bottom)
        }
        val rx = side + leftW + g
        val rh = (bottom - top - 3 * g) / 4
        for (i in 0 until 4) {
            val y = top + i * (rh + g)
            buttons[3 + i].r.set(rx, y, rx + colW, y + rh)
        }
        val areaBottom = bottom - stripH - g
        dcx = side + leftW / 2
        dcy = (top + areaBottom) / 2
        dr = minOf(leftW, areaBottom - top) / 2
    }

    private fun enabled(b: Btn) = when (b.id) { "copy", "cut" -> hasSelection || selecting; else -> true }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        var again = selAnim.running
        val rad = dp(prefs.keyRadiusDp)
        val s = selAnim.value
        again = drawDial(c, s) || again
        for (b in buttons) {
            val pv = b.press.value
            if (b.press.running) again = true
            val inset = dp(1.6f) * pv
            val r = tmp.apply { set(b.r.left + inset, b.r.top + inset, b.r.right - inset, b.r.bottom - inset) }
            when (b.kind) {
                Kind.ARROW -> {
                    if (prefs.keyBorders) { bg.color = theme.shadow; c.drawRoundRect(r.left, r.top + dp(1.2f), r.right, r.bottom + dp(1.2f), rad, rad, bg) }
                    bg.color = blend(theme.surface, theme.surfacePressed, pv)
                }
                Kind.SELECT -> bg.color = blend(blend(theme.soft, theme.accent, s), if (s > 0.5f) theme.accentPressed else theme.softPressed, pv)
                Kind.SOFT -> bg.color = blend(theme.soft, theme.softPressed, pv)
            }
            c.drawRoundRect(r, rad, rad, bg)
            drawLabel(c, b, r, s)
        }
        if (again) postInvalidateOnAnimation()
    }

    private val sector = android.graphics.Path()
    private val ring = RectF()
    private val core = RectF()

    /** Returns true while something on the dial is still animating. */
    private fun drawDial(c: Canvas, s: Float): Boolean {
        var again = false
        bg.color = theme.soft
        c.drawCircle(dcx, dcy, dr, bg)
        ring.set(dcx - dr, dcy - dr, dcx + dr, dcy + dr)
        core.set(dcx - innerR, dcy - innerR, dcx + innerR, dcy + innerR)
        for ((d, b) in arrows) {
            val pv = b.press.value
            if (b.press.running) again = true
            if (pv > 0.01f) {
                val start = when (d) { Dir.RIGHT -> -45f; Dir.DOWN -> 45f; Dir.LEFT -> 135f; Dir.UP -> 225f }
                sector.reset()
                sector.arcTo(ring, start, 90f, true)
                sector.arcTo(core, start + 90f, -90f)
                sector.close()
                bg.color = theme.accentSoft; bg.alpha = (255 * pv).toInt()
                c.drawPath(sector, bg)
                bg.alpha = 255
            }
            val mid = (dr + innerR) / 2
            val (x, y) = when (d) { Dir.UP -> dcx to dcy - mid; Dir.DOWN -> dcx to dcy + mid; Dir.LEFT -> dcx - mid to dcy; Dir.RIGHT -> dcx + mid to dcy }
            ip.color = blend(blend(theme.inkSoft, theme.accent, s), theme.accent, pv)
            Icons.draw(c, b.icon, x, y, dp(24f), ip)
        }
        // hairlines between the sectors
        bg.color = theme.divider
        bg.strokeWidth = dp(1f)
        for (k in 0 until 4) {
            val ang = Math.toRadians(45.0 + 90 * k)
            val cos = Math.cos(ang).toFloat(); val sin = Math.sin(ang).toFloat()
            c.drawLine(dcx + cos * (innerR + dp(5f)), dcy + sin * (innerR + dp(5f)), dcx + cos * (dr - dp(8f)), dcy + sin * (dr - dp(8f)), bg)
        }
        // center: the selection toggle, solid like a key, accent while selecting
        val pv = center.press.value
        if (center.press.running) again = true
        bg.color = blend(blend(theme.surface, theme.accent, s), if (s > 0.5f) theme.accentPressed else theme.surfacePressed, pv)
        c.drawCircle(dcx, dcy, innerR - dp(3f) - dp(1.5f) * pv, bg)
        tp.color = blend(theme.ink, theme.onAccent, s)
        tp.textSize = sp(Ui.BODY); tp.typeface = Ui.medium
        text(c, if (selecting) "选择中" else "选择", dcx, dcy)
        tp.typeface = Ui.regular
        return again
    }

    private fun dialAt(x: Float, y: Float): Btn? {
        val dx = x - dcx; val dy = y - dcy
        val dist = kotlin.math.hypot(dx, dy)
        if (dist > dr + dp(6f)) return null
        if (dist < innerR) return center
        val deg = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
        return arrows[when {
            deg >= -45 && deg < 45 -> Dir.RIGHT
            deg >= 45 && deg < 135 -> Dir.DOWN
            deg >= -135 && deg < -45 -> Dir.UP
            else -> Dir.LEFT
        }]
    }

    private fun drawLabel(c: Canvas, b: Btn, r: RectF, s: Float) {
        val alpha = if (enabled(b)) 255 else 90
        when (b.kind) {
            Kind.ARROW -> {
                ip.color = blend(theme.ink, theme.accent, s); ip.alpha = alpha
                Icons.draw(c, b.icon, r.centerX(), r.centerY(), dp(24f), ip)
            }
            Kind.SELECT -> {
                tp.color = blend(theme.ink, theme.onAccent, s)
                tp.textSize = sp(Ui.BODY); tp.typeface = Ui.medium
                text(c, if (selecting) "结束选择" else "开始选择", r.centerX(), r.centerY())
                tp.typeface = Ui.regular
            }
            Kind.SOFT -> {
                ip.color = theme.inkSoft; ip.alpha = alpha
                tp.color = theme.inkSoft; tp.alpha = alpha
                when (labels) {
                    "icon" -> Icons.draw(c, b.icon, r.centerX(), r.centerY(), dp(22f), ip)
                    "text" -> { tp.textSize = sp(Ui.BODY); text(c, b.text, r.centerX(), r.centerY()) }
                    else -> if (r.height() > dp(50f)) {
                        Icons.draw(c, b.icon, r.centerX(), r.centerY() - dp(9f), dp(20f), ip)
                        tp.textSize = sp(Ui.CAPTION + 0.5f)
                        text(c, b.text, r.centerX(), r.centerY() + dp(12f))
                    } else {
                        tp.textSize = sp(Ui.SMALL)
                        val tw = tp.measureText(b.text)
                        Icons.draw(c, b.icon, r.centerX() - tw / 2 - dp(5f), r.centerY(), dp(17f), ip)
                        text(c, b.text, r.centerX() + dp(9f), r.centerY())
                    }
                }
                tp.alpha = 255; ip.alpha = 255
            }
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    // ---- touch ----
    private var repeats = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val b = pressed ?: return
            fire(b, repeat = true)
            repeats++
            // speeds up a little the longer it is held
            handler.postDelayed(this, if (repeats < 6) 90 else if (repeats < 20) 55 else 35)
        }
    }

    private fun fire(b: Btn, repeat: Boolean = false) {
        if (!enabled(b)) return
        onFeedback?.invoke(if (repeat) "repeat" else "press")
        onAction?.invoke(b.id)
    }

    private fun release(b: Btn?) {
        b?.press?.animateTo(0f, dur(140)); invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val half = dp(prefs.keyGapDp) / 2
                val b = dialAt(e.x, e.y) ?: buttons.firstOrNull { RectF(it.r).apply { inset(-half, -half) }.contains(e.x, e.y) } ?: return true
                pressed = b; repeats = 0
                b.press.animateTo(1f, dur(70))
                if (b.repeat) { fire(b); handler.postDelayed(repeatRunnable, 380) }
                else onFeedback?.invoke("touch")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val b = pressed ?: return true
                if (b.kind == Kind.ARROW) {
                    // sliding round the dial turns the repeat to the new direction
                    val n = dialAt(e.x, e.y)
                    if (n != null && n !== b && n.kind == Kind.ARROW) {
                        b.press.animateTo(0f, dur(120)); n.press.animateTo(1f, dur(70))
                        pressed = n; fire(n); invalidate()
                    } else if (n == null) { handler.removeCallbacks(repeatRunnable); pressed = null; release(b) }
                    return true
                }
                if (b === center) return true
                if (!RectF(b.r).apply { inset(-dp(16f), -dp(16f)) }.contains(e.x, e.y)) {
                    handler.removeCallbacks(repeatRunnable); pressed = null; release(b)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatRunnable)
                val b = pressed
                pressed = null
                if (b != null && !b.repeat) fire(b)
                release(b)
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeatRunnable); release(pressed); pressed = null }
        }
        return true
    }
}
