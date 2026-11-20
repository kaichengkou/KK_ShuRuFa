package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 快捷面板: pages of eight tiles (swipe sideways for the next page). Every tile is the same
 * card: an icon in a disc, a name, and for switches a small toggle in the corner.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(context: Context, private val prefs: Prefs) : View(context) {
    /** [on] is null for shortcuts (open a panel or a page) and true/false for switches. */
    data class Tile(val id: String, val title: String, val sub: String = "", val on: Boolean? = null)

    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onTile: ((String) -> Unit)? = null
    var onPress: (() -> Unit)? = null
    var tiles: List<Tile> = emptyList()
        set(v) { field = v; page.set(page.value.coerceIn(0f, (pages - 1).toFloat())); invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hits = mutableListOf<Pair<RectF, Int>>()
    private var pressed = -1
    private val appear = Tween(1f)
    private val page = Tween(0f)
    private val cols = 4
    private val rows = 2
    private val perPage get() = cols * rows
    private val pages get() = ((tiles.size + perPage - 1) / perPage).coerceAtLeast(1)

    fun animateIn() { if (prefs.animations) { appear.set(0f); appear.animateTo(1f, (220 * prefs.animSpeed).toLong()) }; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val pad = dp(10f); val g = dp(8f)
        val dotsH = if (pages > 1) dp(18f) else dp(4f)
        val w = (width - 2 * pad - g * (cols - 1)) / cols
        val h = (height - 2 * pad - dotsH - g * (rows - 1)) / rows
        val a = appear.value
        val p0 = page.value + dragOffset
        if (appear.running || page.running) postInvalidateOnAnimation()
        tiles.forEachIndexed { i, t ->
            val pg = i / perPage; val k = i % perPage
            val col = k % cols; val row = k / cols
            val shift = (pg - p0) * width
            if (abs(shift) >= width) return@forEachIndexed
            val delay = (k * 0.04f).coerceAtMost(0.35f)
            val pa = ((a - delay) / (1 - delay)).coerceIn(0f, 1f)
            val x = pad + col * (w + g) + shift
            val y = pad + row * (h + g) + (1 - pa) * dp(8f)
            val r = RectF(x, y, x + w, y + h)
            hits += r to i
            drawTile(c, t, r, i == pressed, pa)
        }
        if (pages > 1) {
            // page dots
            val cy = height - pad / 2 - dotsH / 2
            for (i in 0 until pages) {
                val on = (p0.roundToInt() == i)
                bg.color = if (on) theme.accent else theme.divider
                val cx = width / 2f + (i - (pages - 1) / 2f) * dp(14f)
                c.drawRoundRect(cx - (if (on) dp(7f) else dp(3f)), cy - dp(3f), cx + (if (on) dp(7f) else dp(3f)), cy + dp(3f), dp(3f), dp(3f), bg)
            }
        }
    }

    private fun drawTile(c: Canvas, t: Tile, r: RectF, isPressed: Boolean, alpha: Float) {
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        val on = t.on == true
        bg.color = if (isPressed) theme.surfacePressed else theme.surface
        bg.alpha = (bg.alpha * alpha).toInt()
        c.drawRoundRect(r, rad, rad, bg)
        // icon disc
        val dr = dp(16f)
        val dcx = r.left + dp(12f) + dr; val dcy = r.top + dp(12f) + dr
        // soft and divider colors are translucent: scale their own alpha, do not replace it
        bg.color = if (on) theme.accent else theme.soft
        bg.alpha = (bg.alpha * alpha).toInt()
        c.drawCircle(dcx, dcy, dr, bg)
        val glyph = GLYPHS[t.id] ?: GLYPHS[t.id.removePrefix("tool:")]
        val ink = if (on) theme.onAccent else theme.accent
        if (glyph != null) {
            if (Icons.all.containsKey(glyph)) {
                ip.color = ink; ip.alpha = (255 * alpha).toInt()
                Icons.draw(c, glyph, dcx, dcy, dp(18f), ip, filled = glyph == "ai" && on)
            } else {
                tp.textAlign = Paint.Align.CENTER; tp.typeface = Ui.medium; tp.textSize = sp(Ui.BODY + 1f)
                tp.color = ink; tp.alpha = (255 * alpha).toInt()
                text(c, glyph, dcx, dcy)
            }
        }
        // a small toggle for switches
        if (t.on != null) {
            val tw = dp(24f); val th = dp(14f)
            val tr = RectF(r.right - dp(10f) - tw, dcy - th / 2, r.right - dp(10f), dcy + th / 2)
            bg.color = if (on) theme.accent else theme.hint; bg.alpha = (bg.alpha * alpha * (if (on) 1f else 0.45f)).toInt()
            c.drawRoundRect(tr, th / 2, th / 2, bg)
            bg.color = theme.surface; bg.alpha = (255 * alpha).toInt()
            c.drawCircle(if (on) tr.right - th / 2 else tr.left + th / 2, tr.centerY(), th / 2 - dp(2f), bg)
        }
        tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.medium; tp.textSize = sp(Ui.SMALL + 0.5f)
        tp.color = theme.ink; tp.alpha = (255 * alpha).toInt()
        val title = if (t.sub.isNotEmpty()) "${t.title} ${t.sub}" else t.title
        c.drawText(title, r.left + dp(12f), r.bottom - dp(12f), tp)
        tp.typeface = Ui.regular
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    // ---- touch: tap a tile, swipe for pages ----
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
                pressed = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second ?: -1
                if (pressed >= 0) onPress?.invoke()
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
                    if (abs(v) > dp(400f)) to = if (v < 0) cur.toInt() + 1 else cur.toInt()
                    to = to.coerceIn(0, pages - 1)
                    page.set(cur); dragOffset = 0f
                    page.animateTo(to.toFloat(), if (prefs.animations) (240 * prefs.animSpeed).toLong() else 0L)
                } else {
                    val i = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second ?: -1
                    if (i >= 0 && i == pressed) onTile?.invoke(tiles[i].id)
                }
                pressed = -1; swiping = false
                vt?.recycle(); vt = null
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
