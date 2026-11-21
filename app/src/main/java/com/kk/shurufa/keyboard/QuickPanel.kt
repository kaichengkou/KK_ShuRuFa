package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 快捷面板: pages of eight buttons (swipe sideways for the next page). Each is a round button
 * with its name under it; a switch that is on fills with the accent, everything else stays a
 * soft disc. No cards, no separate toggles: the disc is the state.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** [on] is null for shortcuts (open a panel or a page) and true/false for switches. */
    data class Tile(val id: String, val title: String, val sub: String = "", val on: Boolean? = null)

    var onTile: ((String) -> Unit)? = null
    var onPress: (() -> Unit)? = null
    var tiles: List<Tile> = emptyList()
        set(v) {
            // a switch that changes fades into its new state instead of jumping
            v.forEachIndexed { i, t -> fills.getOrPut(t.id) { Tween(if (t.on == true) 1f else 0f) }.animateTo(if (t.on == true) 1f else 0f, dur(Motion.SWAP + 60)) }
            field = v
            page.snap(page.value.coerceIn(0f, (pages - 1).toFloat()))
            invalidate()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hits = Hits()
    private val fills = HashMap<String, Tween>()
    private var pressed = -1
    private val press = Tween(0f)
    private val appear = Tween(1f)
    private val page = Spring(0f, stiffness = 420f, dampingRatio = 0.9f)
    private val cols = 4
    private val rows = 2
    private val perPage get() = cols * rows
    private val pages get() = ((tiles.size + perPage - 1) / perPage).coerceAtLeast(1)

    fun animateIn() {
        if (Motion.on(prefs)) { appear.set(0f); appear.animateTo(1f, dur(Motion.PANEL), Motion.ENTER) }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        if (page.running) page.step()
        val padX = dp(8f)
        val padTop = dp(10f)
        val dotsH = if (pages > 1) dp(20f) else dp(6f)
        val w = (width - 2 * padX) / cols
        val h = (height - padTop - dotsH) / rows
        val a = appear.value
        val p0 = page.value + dragOffset
        val discR = minOf(dp(23f), h * 0.29f)
        var again = appear.running || page.running || press.running
        tiles.forEachIndexed { i, tile ->
            val pg = i / perPage; val k = i % perPage
            val col = k % cols; val row = k / cols
            val shift = (pg - p0) * width
            if (abs(shift) >= width) return@forEachIndexed
            // the buttons rise into place one after another
            val delay = (k * 0.045f).coerceAtMost(0.35f)
            val pa = ((a - delay) / (1 - delay)).coerceIn(0f, 1f)
            val x = padX + col * w + shift
            val y = padTop + row * h + (1 - pa) * dp(10f)
            hits.add(i.toString(), x, y, x + w, y + h)
            val fill = fills[tile.id]
            if (fill?.running == true) again = true
            drawTile(c, tile, x, y, w, h, discR, if (i == pressed) press.value else 0f, fill?.value ?: 0f, pa)
        }
        if (pages > 1) {
            // page dots: the current one is a short pill that slides with the pages
            val cy = height - dotsH / 2 - dp(2f)
            val gap = dp(14f)
            val pos = p0.coerceIn(0f, (pages - 1).toFloat())
            for (i in 0 until pages) {
                val near = (1f - abs(pos - i)).coerceIn(0f, 1f)
                bg.color = Colors.blend(t.divider, t.accent, near)
                val cx = width / 2f + (i - (pages - 1) / 2f) * gap
                val half = dp(3f) + dp(4f) * near
                c.drawRoundRect(cx - half, cy - dp(3f), cx + half, cy + dp(3f), dp(3f), dp(3f), bg)
            }
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun drawTile(c: Canvas, tile: Tile, x: Float, y: Float, w: Float, h: Float, r: Float, pv: Float, on: Float, alpha: Float) {
        val t = theme
        val cx = x + w / 2
        val cy = y + h * 0.42f
        val rr = r * (1f - 0.06f * pv)
        // off and shortcuts: a soft disc; on: the accent
        val off = Colors.blend(t.soft, t.softPressed, pv)
        val lit = Colors.blend(t.accent, t.accentPressed, pv)
        bg.color = Colors.fade(Colors.blend(off, lit, on), alpha)
        c.drawCircle(cx, cy, rr, bg)
        val ink = Colors.blend(t.inkSoft, t.onAccent, on)
        val glyph = GLYPHS[tile.id] ?: GLYPHS[tile.id.removePrefix("tool:")]
        if (glyph != null) {
            if (Icons.all.containsKey(glyph)) {
                ip.color = Colors.fade(ink, alpha)
                Icons.draw(c, glyph, cx, cy, r * 0.9f, ip, filled = glyph == "ai" && on > 0.5f)
            } else {
                tp.typeface = Ui.medium; tp.textSize = sp(Ui.TITLE + 1f)
                tp.color = Colors.fade(ink, alpha)
                drawText(c, glyph, cx, cy, tp)
            }
        }
        tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL + 0.5f)
        tp.color = Colors.fade(t.ink, alpha)
        val title = if (tile.sub.isNotEmpty()) "${tile.title} · ${tile.sub}" else tile.title
        val maxW = w - dp(6f)
        val tw = tp.measureText(title)
        if (tw > maxW) tp.textSize *= maxW / tw
        drawText(c, title, cx, cy + r + dp(14f), tp)
    }

    // ---- touch: tap a button, swipe for pages ----
    private var downX = 0f
    private var dragOffset = 0f
    private var swiping = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; swiping = false; dragOffset = 0f
                page.snap(page.value)
                pressed = hits.at(e.x, e.y)?.toInt() ?: -1
                if (pressed >= 0) { onPress?.invoke(); press.set(0f); press.animateTo(1f, dur(Motion.PRESS)) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && pages > 1 && abs(e.x - downX) > slop) { swiping = true; pressed = -1 }
                if (swiping) {
                    var off = -(e.x - downX) / width
                    val target = page.value + off
                    if (target < 0f || target > pages - 1f) off *= 0.3f
                    dragOffset = off; invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    val cur = page.value + dragOffset
                    var to = cur.roundToInt()
                    if (abs(v) > dp(400f)) to = if (v < 0) floor(cur).toInt() + 1 else floor(cur).toInt()
                    to = to.coerceIn(0, pages - 1)
                    page.snap(cur); dragOffset = 0f
                    page.enabled = Motion.on(prefs)
                    page.animateTo(to.toFloat(), -v / width)
                } else {
                    val i = hits.at(e.x, e.y)?.toInt() ?: -1
                    if (i >= 0 && i == pressed) onTile?.invoke(tiles[i].id)
                    press.animateTo(0f, dur(Motion.RELEASE))
                }
                swiping = false
                vt?.recycle(); vt = null
                postDelayed({ pressed = -1; invalidate() }, dur(Motion.RELEASE))
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; swiping = false; dragOffset = 0f; vt?.recycle(); vt = null; invalidate() }
        }
        return true
    }

    companion object {
        /** Tile id → an icon name, or a short text glyph. */
        val GLYPHS = mapOf(
            "trad" to "繁", "emoji" to "emoji", "predict" to "ai", "correct" to "check", "pinyin" to "ā", "stress" to "á",
            "theme" to "moon", "vibrate" to "vibrate", "sound" to "speaker",
            "ai" to "ai", "mic" to "mic", "translate" to "translate", "cursor" to "cursor", "clipboard" to "clipboard",
            "symbols" to "§", "math" to "π", "undo" to "undo", "emoji_panel" to "emoji",
            "picker" to "abc", "settings" to "settings", "toolbar" to "grid",
        )
    }
}
