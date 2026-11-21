package com.kk.shurufa.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A scrolling pick list drawn inside a panel (models, recognizers, engines): one row per
 * choice, its short name on the left and the service on the right, a check on the current one.
 * Drag or fling to scroll, tap to pick. The host draws it with [draw] and hands it touches
 * that start inside it with [onTouch].
 */
class PickList(private val host: View) {
    class Item(val id: String, val name: String, val sub: String = "")

    var items: List<Item> = emptyList()
        set(v) { field = v; clamp() }
    var current = ""
    var title = ""
    /** Row height in dp. */
    var rowDp = 40f

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, host.resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, host.resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(host.context)
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(host.context).scaledMinimumFlingVelocity
    private var vt: VelocityTracker? = null

    private var card = RectF()
    private var list = RectF()
    private var scroll = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressed = -1
    private var owns = false

    private val rowH get() = dp(rowDp)
    private val maxScroll get() = max(0f, items.size * rowH - list.height())
    private fun clamp() { scroll = scroll.coerceIn(0f, maxScroll) }

    /** Brings the current choice into view (call when the list opens). */
    fun reveal() {
        scroller.forceFinished(true)
        val i = items.indexOfFirst { it.id == current }
        scroll = if (i < 0 || list.height() <= 0f) 0f else (i * rowH - (list.height() - rowH) / 2)
        clamp()
        pendingReveal = list.height() <= 0f
    }
    private var pendingReveal = false

    fun contains(x: Float, y: Float) = card.contains(x, y)

    /** Draws the list in [area]; true while it is still moving. */
    fun draw(c: Canvas, area: RectF, theme: KbTheme, radius: Float): Boolean {
        card = RectF(area)
        bg.color = theme.surface
        c.drawRoundRect(card, radius, radius, bg)
        var top = card.top + dp(4f)
        if (title.isNotEmpty()) {
            tp.textSize = sp(Ui.CAPTION); tp.typeface = Ui.medium; tp.color = theme.hint; tp.textAlign = Paint.Align.LEFT
            c.drawText(title, card.left + dp(14f), card.top + dp(17f), tp)
            top = card.top + dp(24f)
        }
        list = RectF(card.left + dp(4f), top, card.right - dp(4f), card.bottom - dp(4f))
        if (pendingReveal) reveal()
        var moving = false
        if (scroller.computeScrollOffset()) { scroll = scroller.currY.toFloat(); clamp(); moving = !scroller.isFinished }
        c.save()
        c.clipRect(list)
        val first = (scroll / rowH).toInt().coerceAtLeast(0)
        val last = min(items.size - 1, ((scroll + list.height()) / rowH).toInt())
        for (i in first..last) {
            val it = items[i]
            val y = list.top + i * rowH - scroll
            val r = RectF(list.left, y, list.right, y + rowH)
            val on = it.id == current
            if (i == pressed || on) {
                bg.color = if (i == pressed) theme.softPressed else theme.accentSoft
                val rr = RectF(r).apply { inset(dp(2f), dp(2f)) }
                c.drawRoundRect(rr, dp(10f), dp(10f), bg)
            } else if (i > 0 && items[i - 1].id != current && i - 1 != pressed) {
                bg.color = theme.divider
                c.drawRect(r.left + dp(12f), r.top - dp(0.4f), r.right - dp(12f), r.top + dp(0.4f), bg)
            }
            // check · name ··········· service
            val checkW = dp(26f)
            if (on) { ip.color = theme.accent; Icons.draw(c, "check", r.left + dp(18f), r.centerY(), dp(15f), ip) }
            tp.textAlign = Paint.Align.RIGHT; tp.textSize = sp(Ui.CAPTION + 0.5f); tp.typeface = Ui.regular
            tp.color = if (on) theme.accent else theme.hint
            val subW = if (it.sub.isEmpty()) 0f else min(tp.measureText(it.sub), r.width() * 0.35f)
            val sub = TextUtils.ellipsize(it.sub, tp, subW, TextUtils.TruncateAt.END).toString()
            val fm0 = tp.fontMetrics
            c.drawText(sub, r.right - dp(12f), r.centerY() - (fm0.ascent + fm0.descent) / 2, tp)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.BODY); tp.typeface = if (on) Ui.medium else Ui.regular
            tp.color = if (on) theme.accent else theme.ink
            val nameX = r.left + dp(8f) + checkW
            val avail = r.right - dp(12f) - subW - dp(10f) - nameX
            val name = TextUtils.ellipsize(it.name, tp, max(0f, avail), TextUtils.TruncateAt.END).toString()
            val fm = tp.fontMetrics
            c.drawText(name, nameX, r.centerY() - (fm.ascent + fm.descent) / 2, tp)
        }
        c.restore()
        // a thin bar when there is more than fits
        if (maxScroll > 0f) {
            val total = items.size * rowH
            val h = max(dp(18f), list.height() * list.height() / total)
            val y = list.top + (list.height() - h) * (scroll / maxScroll)
            bg.color = theme.divider
            c.drawRoundRect(list.right - dp(3f), y, list.right - dp(0.5f), y + h, dp(2f), dp(2f), bg)
        }
        tp.typeface = Ui.regular
        return moving
    }

    /**
     * Touches for the list: a gesture that starts inside it stays with it. Returns the id
     * picked by a tap, or null.
     */
    fun onTouch(e: MotionEvent): String? {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                owns = card.contains(e.x, e.y)
                if (!owns) return null
                scroller.forceFinished(true)
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downY = e.y; lastY = e.y; dragging = false
                pressed = rowAt(e.y)
                host.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!owns) return null
                vt?.addMovement(e)
                if (!dragging && abs(e.y - downY) > slop) { dragging = true; pressed = -1 }
                if (dragging) { scroll -= e.y - lastY; clamp(); host.invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!owns) return null
                owns = false
                vt?.addMovement(e)
                var pick: String? = null
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    if (abs(v) > minFling) {
                        scroller.fling(0, scroll.toInt(), 0, -v.toInt(), 0, 0, 0, maxScroll.toInt())
                    }
                } else {
                    val i = rowAt(e.y)
                    if (i >= 0 && i == pressed) pick = items[i].id
                }
                vt?.recycle(); vt = null
                pressed = -1; dragging = false
                host.invalidate()
                return pick
            }
            MotionEvent.ACTION_CANCEL -> { owns = false; pressed = -1; dragging = false; vt?.recycle(); vt = null; host.invalidate() }
        }
        return null
    }

    /** True while a gesture that began inside the list is going on. */
    val busy get() = owns

    private fun rowAt(y: Float): Int {
        if (y < list.top || y > list.bottom) return -1
        val i = ((y - list.top + scroll) / rowH).toInt()
        return if (i in items.indices) i else -1
    }
}
