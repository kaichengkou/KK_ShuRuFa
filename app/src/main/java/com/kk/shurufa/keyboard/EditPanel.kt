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
 * 文字编辑. A header with a way back; a card holding the four arrows around the 开始选择 toggle,
 * with 行首 · 全选 · 行尾 along its bottom; and a column of 删除 / 复制 / 剪切 / 粘贴 on the right.
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

    private class Btn(val id: String, val icon: String?, val text: String, val r: RectF, val repeat: Boolean)
    private val buttons = mutableListOf<Btn>()
    private var card = RectF()
    private var pressed: Btn? = null

    private val headerH get() = dp(40f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutButtons()

    private fun layoutButtons() {
        buttons.clear()
        val pad = dp(8f)
        val gap = dp(prefs.keyGapDp)
        val top = headerH
        val bottom = height - pad
        buttons += Btn("back", "left", "返回", RectF(width - dp(52f), dp(4f), width - dp(8f), headerH - dp(4f)), false)
        val colW = (width - 2 * pad) * 0.27f
        card = RectF(pad, top, width - pad - colW - gap, bottom)
        // right column
        val rx = card.right + gap
        val ids = listOf(Triple("backspace", "backspace", "删除"), Triple("copy", "copy", "复制"), Triple("cut", "cut", "剪切"), Triple("paste", "paste", "粘贴"))
        val rh = (bottom - top - gap * (ids.size - 1)) / ids.size
        ids.forEachIndexed { i, (id, icon, text) ->
            val y = top + i * (rh + gap)
            buttons += Btn(id, icon, text, RectF(rx, y, rx + colW, y + rh), id == "backspace")
        }
        // the card: arrows around the toggle, a strip of three along the bottom
        val stripH = (card.height() * 0.24f).coerceIn(dp(40f), dp(56f))
        val area = RectF(card.left, card.top, card.right, card.bottom - stripH)
        val cx = area.centerX(); val cy = area.centerY()
        val cw = (area.width() * 0.36f).coerceAtMost(dp(150f))
        val ch = (area.height() * 0.36f).coerceIn(dp(40f), dp(60f))
        val center = RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        buttons += Btn("select", null, "开始选择", center, false)
        val aw = (area.width() - cw) / 2
        val ah = (area.height() - ch) / 2
        buttons += Btn("up", "up", "", RectF(center.left, area.top, center.right, center.top), true)
        buttons += Btn("down", "down", "", RectF(center.left, center.bottom, center.right, area.bottom), true)
        buttons += Btn("left", "left", "", RectF(area.left, area.top + ah * 0.4f, area.left + aw, area.bottom - ah * 0.4f), true)
        buttons += Btn("right", "right", "", RectF(area.right - aw, area.top + ah * 0.4f, area.right, area.bottom - ah * 0.4f), true)
        val sw = card.width() / 3
        listOf(Triple("home", "home", "行首"), Triple("select_all", "select_all", "全选"), Triple("end", "end", "行尾")).forEachIndexed { i, (id, icon, text) ->
            buttons += Btn(id, icon, text, RectF(card.left + i * sw, card.bottom - stripH, card.left + (i + 1) * sw, card.bottom), false)
        }
    }

    private fun enabled(b: Btn) = when (b.id) { "copy", "cut" -> hasSelection || selecting; else -> true }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        if (selAnim.running) postInvalidateOnAnimation()
        // header
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.TITLE); tp.color = theme.ink; tp.typeface = Ui.medium
        val fm0 = tp.fontMetrics
        c.drawText("文字编辑", dp(16f), headerH / 2 - (fm0.ascent + fm0.descent) / 2, tp)
        tp.typeface = Ui.regular; tp.textAlign = Paint.Align.CENTER
        val rad = dp(prefs.keyRadiusDp)
        // card
        bg.color = theme.surface
        c.drawRoundRect(card, Ui.radiusLarge(rad, dp(1f)), Ui.radiusLarge(rad, dp(1f)), bg)
        val strip = buttons.first { it.id == "home" }.r
        bg.color = theme.divider
        c.drawRect(card.left + dp(12f), strip.top, card.right - dp(12f), strip.top + dp(0.8f), bg)
        for (id in listOf("home", "select_all")) {
            val r = buttons.first { it.id == id }.r
            c.drawRect(r.right - dp(0.4f), r.top + dp(12f), r.right + dp(0.4f), r.bottom - dp(12f), bg)
        }
        for (b in buttons) drawButton(c, b, rad)
    }

    private fun drawButton(c: Canvas, b: Btn, rad: Float) {
        val on = pressed == b
        val en = enabled(b)
        val r = b.r
        when (b.id) {
            "back" -> {
                if (on) { bg.color = theme.soft; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg) }
                ip.color = theme.inkSoft
                Icons.draw(c, "left", r.centerX(), r.centerY(), dp(22f), ip)
                return
            }
            "select" -> {
                val s = selAnim.value
                bg.color = blend(theme.soft, theme.accent, s)
                if (on) bg.color = if (selecting) theme.accentPressed else theme.softPressed
                c.drawRoundRect(r, rad + dp(4f), rad + dp(4f), bg)
                tp.textSize = sp(Ui.BODY)
                tp.color = blend(theme.ink, theme.onAccent, s)
                text(c, if (selecting) "结束选择" else "开始选择", r.centerX(), r.centerY())
                return
            }
            "up", "down", "left", "right" -> {
                if (on) { bg.color = theme.soft; c.drawRoundRect(r, rad, rad, bg) }
                ip.color = if (on) theme.accent else if (selecting) theme.accent else theme.inkSoft
                Icons.draw(c, b.icon!!, r.centerX(), r.centerY(), dp(26f), ip)
                return
            }
            "home", "select_all", "end" -> {
                if (on) { bg.color = theme.soft; c.drawRect(r, bg) }
            }
            else -> {
                bg.color = if (on) theme.softPressed else theme.soft
                c.drawRoundRect(r, rad, rad, bg)
            }
        }
        val alpha = if (en) 255 else 90
        ip.color = theme.inkSoft; ip.alpha = alpha
        tp.color = theme.ink; tp.alpha = alpha
        when (labels) {
            "icon" -> Icons.draw(c, b.icon!!, r.centerX(), r.centerY(), dp(22f), ip)
            "text" -> { tp.textSize = sp(Ui.BODY); text(c, b.text, r.centerX(), r.centerY()) }
            else -> {
                if (r.height() > dp(48f)) {
                    Icons.draw(c, b.icon!!, r.centerX(), r.centerY() - dp(9f), dp(20f), ip)
                    tp.textSize = sp(Ui.SMALL); tp.color = theme.inkSoft; tp.alpha = alpha
                    text(c, b.text, r.centerX(), r.centerY() + dp(13f))
                } else {
                    tp.textSize = sp(Ui.BODY)
                    val tw = tp.measureText(b.text)
                    Icons.draw(c, b.icon!!, r.centerX() - tw / 2 - dp(6f), r.centerY(), dp(18f), ip)
                    text(c, b.text, r.centerX() + dp(8f), r.centerY())
                }
            }
        }
        tp.alpha = 255; ip.alpha = 255
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = buttons.firstOrNull { it.r.contains(e.x, e.y) } ?: return true
                pressed = b; repeats = 0
                if (b.repeat) { fire(b); handler.postDelayed(repeatRunnable, 380) }
                else onFeedback?.invoke("touch")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val b = pressed ?: return true
                if (!RectF(b.r).apply { inset(-dp(16f), -dp(16f)) }.contains(e.x, e.y)) {
                    handler.removeCallbacks(repeatRunnable); pressed = null; invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatRunnable)
                val b = pressed
                pressed = null
                if (b != null && !b.repeat) fire(b)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeatRunnable); pressed = null; invalidate() }
        }
        return true
    }
}
