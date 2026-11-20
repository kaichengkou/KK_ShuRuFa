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

/**
 * 快捷面板: tiles for switches (filled with the accent when on) and shortcuts (solid).
 * Opened from the right-most toolbar button; tapping that button again closes it.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(context: Context, private val prefs: Prefs) : View(context) {
    /** [on] is null for shortcuts (open a panel or a page) and true/false for switches. */
    data class Tile(val id: String, val title: String, val sub: String = "", val on: Boolean? = null)

    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onTile: ((String) -> Unit)? = null
    var onPress: (() -> Unit)? = null
    var tiles: List<Tile> = emptyList()
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rects = mutableListOf<RectF>()
    private var pressed = -1
    private val appear = Tween(1f)
    private val cols = 4

    fun animateIn() { if (prefs.animations) { appear.set(0f); appear.animateTo(1f, (220 * prefs.animSpeed).toLong()) }; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        rects.clear()
        val pad = dp(8f); val g = dp(7f)
        val rowsN = (tiles.size + cols - 1) / cols
        val w = (width - 2 * pad - g * (cols - 1)) / cols
        val h = ((height - 2 * pad - g * (rowsN - 1)) / rowsN).coerceAtMost(dp(72f))
        val a = appear.value
        if (appear.running) postInvalidateOnAnimation()
        tiles.forEachIndexed { i, t ->
            val col = i % cols; val row = i / cols
            // tiles settle in with a small stagger
            val delay = ((row * cols + col) * 0.04f).coerceAtMost(0.4f)
            val p = ((a - delay) / (1 - delay)).coerceIn(0f, 1f)
            val x = pad + col * (w + g)
            val y = pad + row * (h + g) + (1 - p) * dp(8f)
            val r = RectF(x, y, x + w, y + h)
            rects += r
            val on = t.on == true
            bg.color = when {
                on -> if (i == pressed) theme.accentPressed else theme.accent
                t.on == false -> if (i == pressed) theme.softPressed else theme.soft
                else -> if (i == pressed) theme.surfacePressed else theme.surface
            }
            bg.alpha = (bg.alpha * p).toInt()
            c.drawRoundRect(r, Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)), Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)), bg)
            val ink = if (on) theme.onAccent else theme.ink
            // a glyph says at a glance what the tile is; the state is the fill (accent = on)
            val gx = r.left + dp(12f) + dp(10f)
            val gy = r.top + r.height() * 0.34f
            val glyph = GLYPHS[t.id]
            if (glyph != null) {
                if (Icons.all.containsKey(glyph)) {
                    ip.color = if (on) theme.onAccent else theme.accent; ip.alpha = (255 * p).toInt()
                    Icons.draw(c, glyph, gx, gy, dp(19f), ip, filled = glyph == "ai")
                } else {
                    tp.textAlign = Paint.Align.CENTER; tp.typeface = Ui.medium; tp.textSize = sp(Ui.TITLE)
                    tp.color = if (on) theme.onAccent else theme.accent; tp.alpha = (255 * p).toInt()
                    val fm = tp.fontMetrics
                    c.drawText(glyph, gx, gy - (fm.ascent + fm.descent) / 2, tp)
                    tp.textAlign = Paint.Align.LEFT
                }
            }
            if (t.on != null) {
                // a small switch mark in the corner
                val sx = r.right - dp(16f); val sy = r.top + dp(14f)
                bg.color = if (on) theme.onAccent else theme.hint; bg.alpha = ((if (on) 230 else 120) * p).toInt()
                c.drawCircle(sx, sy, dp(3.2f), bg)
            }
            tp.color = ink; tp.alpha = (255 * p).toInt()
            tp.textSize = sp(Ui.SMALL + 0.5f); tp.typeface = Ui.medium
            val title = if (t.sub.isNotEmpty() && t.on == null && t.id == "theme") "${t.title} · ${t.sub}" else t.title
            c.drawText(title, r.left + dp(12f), r.bottom - dp(12f), tp)
            tp.typeface = Ui.regular
        }
    }

    companion object {
        /** Tile id → an icon name, or a short text glyph. */
        val GLYPHS = mapOf(
            "trad" to "繁", "emoji" to "emoji", "predict" to "ai", "correct" to "check", "pinyin" to "ā", "stress" to "á",
            "latex" to "∑", "theme" to "moon", "vibrate" to "vibrate", "sound" to "speaker", "math" to "π", "symbols" to "§",
            "cursor" to "cursor", "clipboard" to "clipboard", "picker" to "abc", "settings" to "settings",
            "voice" to "mic", "translate" to "translate", "toolbar" to "grid",
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = rects.indexOfFirst { it.contains(e.x, e.y) }; if (pressed >= 0) onPress?.invoke(); invalidate() }
            MotionEvent.ACTION_UP -> {
                val i = rects.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0 && i == pressed) {
                    onTile?.invoke(tiles[i].id)
                }
                pressed = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
