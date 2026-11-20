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
 * 文字编辑, built from the same keys as the keyboard (same gaps, radius, solid and soft styles):
 *
 *   行首  ↑   行尾 │ 删除
 *    ←  选择   →  │ 复制
 *   全选  ↓   撤销 │ 剪切
 *                 │ 粘贴
 *
 * Arrows are solid like letter keys, everything else is soft; 选择 turns accent while selecting.
 * Arrows and 删除 repeat while held. 复制 and 剪切 are dimmed while nothing is selected.
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
        Btn("home", "home", "行首", Kind.SOFT, false), Btn("up", "up", "", Kind.ARROW, true), Btn("end", "end", "行尾", Kind.SOFT, false),
        Btn("left", "left", "", Kind.ARROW, true), Btn("select", "cursor", "开始选择", Kind.SELECT, false), Btn("right", "right", "", Kind.ARROW, true),
        Btn("select_all", "select_all", "全选", Kind.SOFT, false), Btn("down", "down", "", Kind.ARROW, true), Btn("undo", "undo", "撤销", Kind.SOFT, false),
        Btn("backspace", "backspace", "删除", Kind.SOFT, true), Btn("copy", "copy", "复制", Kind.SOFT, false),
        Btn("cut", "cut", "剪切", Kind.SOFT, false), Btn("paste", "paste", "粘贴", Kind.SOFT, false),
    )
    private var pressed: Btn? = null
    private val tmp = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutButtons()

    private fun layoutButtons() {
        // the keyboard's own margins, so the edges line up with the letters
        val side = dp(4f); val top = dp(6f); val bottom = height - dp(prefs.bottomPaddingDp + 2f)
        val g = dp(prefs.keyGapDp)
        val unit = (width - 2 * side - 3 * g) / 4.4f
        val colW = floatArrayOf(unit, unit * 1.4f, unit, unit)
        val x = FloatArray(4); var cx = side
        for (i in 0 until 4) { x[i] = cx; cx += colW[i] + g }
        val lh = (bottom - top - 2 * g) / 3
        for (i in 0 until 9) {
            val c = i % 3; val r = i / 3
            val y = top + r * (lh + g)
            buttons[i].r.set(x[c], y, x[c] + colW[c], y + lh)
        }
        val rh = (bottom - top - 3 * g) / 4
        for (i in 0 until 4) {
            val y = top + i * (rh + g)
            buttons[9 + i].r.set(x[3], y, x[3] + colW[3], y + rh)
        }
    }

    private fun enabled(b: Btn) = when (b.id) { "copy", "cut" -> hasSelection || selecting; else -> true }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        var again = selAnim.running
        val rad = dp(prefs.keyRadiusDp)
        val s = selAnim.value
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
                val b = buttons.firstOrNull { RectF(it.r).apply { inset(-half, -half) }.contains(e.x, e.y) } ?: return true
                pressed = b; repeats = 0
                b.press.animateTo(1f, dur(70))
                if (b.repeat) { fire(b); handler.postDelayed(repeatRunnable, 380) }
                else onFeedback?.invoke("touch")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val b = pressed ?: return true
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
