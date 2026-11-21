package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.max
import kotlin.math.min

/**
 * 快捷面板: everything on one page, in the order it is reached for. Switches first, as chips that
 * fill with the accent when on (the chip is the state); then rows of things to insert or open,
 * each row named at its start; links to the settings last, small. No pages to swipe through.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** [on] is null for actions and true/false for switches; [sub] is a value shown after the name. */
    data class Tile(val id: String, val title: String, val sub: String = "", val on: Boolean? = null)

    enum class Kind { SWITCHES, ROW, FOOTER }
    class Section(val kind: Kind, val label: String, val tiles: List<Tile>)

    var onTile: ((String) -> Unit)? = null
    var onPress: (() -> Unit)? = null
    var sections: List<Section> = emptyList()
        set(v) {
            // a switch that changes fades into its new state instead of jumping
            v.flatMap { it.tiles }.forEach { t -> fills.getOrPut(t.id) { Tween(if (t.on == true) 1f else 0f) }.animateTo(if (t.on == true) 1f else 0f, dur(Motion.SWAP + 60)) }
            field = v
            invalidate()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hits = Hits()
    private val fills = HashMap<String, Tween>()
    private var pressed: String? = null
    private val press = Tween(0f)
    private val appear = Tween(1f)
    private val r = RectF()

    fun animateIn() {
        if (Motion.on(prefs)) { appear.set(0f); appear.animateTo(1f, dur(Motion.PANEL), Motion.ENTER) }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        val padX = dp(12f)
        val gap = dp(8f)
        val switches = sections.firstOrNull { it.kind == Kind.SWITCHES }
        val rows = sections.filter { it.kind == Kind.ROW && it.tiles.isNotEmpty() }
        val footer = sections.firstOrNull { it.kind == Kind.FOOTER }
        val switchRows = ((switches?.tiles?.size ?: 0) + 3) / 4
        // heights fit the panel: switch chips shrink a little on a short keyboard, never below 34dp
        val rowH = dp(34f); val footH = dp(30f)
        val fixed = dp(10f) + rows.size * (rowH + gap) + (if (footer != null) footH + dp(4f) else 0f) + dp(6f) +
            rows.count { rowWidth(it) > width - 2 * padX - dp(40f) } * (rowH + dp(6f))
        val sw = ((height - fixed) / max(1, switchRows) - gap).coerceIn(dp(34f), dp(44f))
        val a = appear.value
        var again = appear.running || press.running
        var y = dp(10f)
        var n = 0
        // switches: 4 to a row
        switches?.let { s ->
            val w = (width - 2 * padX - 3 * gap) / 4
            s.tiles.forEachIndexed { i, tile ->
                val col = i % 4; val row = i / 4
                val x = padX + col * (w + gap)
                val ty = y + row * (sw + gap) + rise(a, n++)
                r.set(x, ty, x + w, ty + sw)
                val fill = fills[tile.id]
                if (fill?.running == true) again = true
                drawSwitch(c, tile, r, fill?.value ?: 0f, alpha(a, n))
                hits.add(tile.id, r)
            }
            y += switchRows * (sw + gap) + dp(2f)
        }
        // rows of actions: a quiet name, then chips as wide as their words
        for (row in rows) {
            tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.medium; tp.textSize = sp(Ui.SMALL); tp.color = t.hint
            drawText(c, row.label, padX + dp(2f), y + rowH / 2, tp)
            var x = padX + dp(40f)
            for (tile in row.tiles) {
                tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL + 1f)
                // in a row, only a real icon: a text glyph (日 before 日期) would say the name twice
                val glyph = glyphOf(tile.id)?.takeIf { Icons.all.containsKey(it) }
                val cw = tp.measureText(tile.title) + dp(if (glyph != null) 42f else 26f)
                if (x + cw > width - padX) { x = padX + dp(40f); y += rowH + dp(6f) }
                val up = rise(a, n++)
                r.set(x, y + up, x + cw, y + rowH + up)
                drawChip(c, tile, r, glyph, alpha(a, n))
                hits.add(tile.id, r)
                x += cw + dp(6f)
            }
            y += rowH + gap
        }
        // the way to the settings, last and small
        footer?.let { f ->
            val fy = height - footH - dp(6f)
            var x = width - padX
            for (tile in f.tiles.reversed()) {
                tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL)
                val glyph = glyphOf(tile.id)
                val cw = tp.measureText(tile.title) + dp(if (glyph != null) 36f else 20f)
                r.set(x - cw, fy, x, fy + footH)
                drawLink(c, tile, r, glyph, alpha(a, n++))
                hits.add(tile.id, r)
                x -= cw + dp(4f)
            }
        }
        if (again) postInvalidateOnAnimation()
    }

    /** How wide a row's chips are together (to know whether it needs a second line). */
    private fun rowWidth(s: Section): Float {
        tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL + 1f)
        return s.tiles.sumOf { t ->
            val g = glyphOf(t.id)?.takeIf { Icons.all.containsKey(it) }
            (tp.measureText(t.title) + dp(if (g != null) 42f else 26f) + dp(6f)).toDouble()
        }.toFloat()
    }

    /** Things arrive one after another, from a little lower. */
    private fun rise(a: Float, i: Int): Float = (1f - stagger(a, i)) * dp(8f)
    private fun alpha(a: Float, i: Int) = stagger(a, i)
    private fun stagger(a: Float, i: Int): Float { val d = (i * 0.03f).coerceAtMost(0.4f); return ((a - d) / (1 - d)).coerceIn(0f, 1f) }

    private fun glyphOf(id: String) = GLYPHS[id] ?: GLYPHS[id.removePrefix("tool:")]

    private fun pressOf(id: String) = if (pressed == id) press.value else 0f

    private fun drawSwitch(c: Canvas, tile: Tile, rr: RectF, on: Float, alpha: Float) {
        val t = theme
        val pv = pressOf(tile.id)
        val rad = min(rr.height() / 2, dp(14f))
        rr.inset(pv * dp(1f), pv * dp(1f))
        val off = Colors.blend(t.soft, t.softPressed, pv)
        val lit = Colors.blend(t.accent, t.accentPressed, pv)
        bg.color = Colors.fade(Colors.blend(off, lit, on), alpha)
        c.drawRoundRect(rr, rad, rad, bg)
        val ink = Colors.fade(Colors.blend(t.ink, t.onAccent, on), alpha)
        val glyph = glyphOf(tile.id)
        val gx = rr.left + dp(18f)
        if (glyph != null) drawGlyph(c, glyph, gx, rr.centerY(), dp(16f), ink, filled = glyph == "ai" && on > 0.5f)
        tp.textAlign = Paint.Align.LEFT; tp.typeface = if (on > 0.5f) Ui.medium else Ui.regular; tp.textSize = sp(Ui.SMALL + 1f)
        tp.color = ink
        val title = if (tile.sub.isNotEmpty()) "${tile.title} ${tile.sub}" else tile.title
        val left = if (glyph != null) gx + dp(14f) else rr.left + dp(12f)
        val maxW = rr.right - dp(8f) - left
        val tw = tp.measureText(title)
        if (tw > maxW) tp.textSize *= maxW / tw
        drawText(c, title, left, rr.centerY(), tp)
        tp.typeface = Ui.regular
    }

    private fun drawChip(c: Canvas, tile: Tile, rr: RectF, glyph: String?, alpha: Float) {
        val t = theme
        val pv = pressOf(tile.id)
        bg.color = Colors.fade(Colors.blend(t.soft, t.softPressed, pv), alpha)
        c.drawRoundRect(rr, rr.height() / 2, rr.height() / 2, bg)
        val ink = Colors.fade(t.ink, alpha)
        var tx = rr.left + dp(13f)
        if (glyph != null) { drawGlyph(c, glyph, rr.left + dp(19f), rr.centerY(), dp(15f), Colors.fade(t.inkSoft, alpha)); tx = rr.left + dp(31f) }
        tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL + 1f); tp.color = ink
        drawText(c, tile.title, tx, rr.centerY(), tp)
    }

    private fun drawLink(c: Canvas, tile: Tile, rr: RectF, glyph: String?, alpha: Float) {
        val t = theme
        val pv = pressOf(tile.id)
        if (pv > 0.01f) { bg.color = Colors.fade(t.soft, alpha * pv); c.drawRoundRect(rr, rr.height() / 2, rr.height() / 2, bg) }
        val ink = Colors.fade(t.inkSoft, alpha)
        var tx = rr.left + dp(10f)
        if (glyph != null) { drawGlyph(c, glyph, rr.left + dp(16f), rr.centerY(), dp(14f), ink); tx = rr.left + dp(26f) }
        tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.regular; tp.textSize = sp(Ui.SMALL); tp.color = ink
        drawText(c, tile.title, tx, rr.centerY(), tp)
    }

    /** An icon by name, or a short text glyph (繁, 日, 壹 …). */
    private fun drawGlyph(c: Canvas, glyph: String, cx: Float, cy: Float, size: Float, color: Int, filled: Boolean = false) {
        if (Icons.all.containsKey(glyph)) {
            ip.color = color
            Icons.draw(c, glyph, cx, cy, size, ip, filled = filled)
        } else {
            tp.textAlign = Paint.Align.CENTER; tp.typeface = Ui.medium; tp.textSize = size * 0.95f; tp.color = color
            drawText(c, glyph, cx, cy, tp)
            tp.typeface = Ui.regular
        }
    }

    // ---- touch: a tap on a chip ----
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                if (pressed != null) { onPress?.invoke(); press.set(0f); press.animateTo(1f, dur(Motion.PRESS)) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val id = hits.at(e.x, e.y)
                if (id != null && id == pressed) onTile?.invoke(id)
                press.animateTo(0f, dur(Motion.RELEASE))
                postDelayed({ pressed = null; invalidate() }, dur(Motion.RELEASE))
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
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
            "incognito" to "隐", "date" to "日", "time" to "时", "week" to "周", "lunar" to "农", "calc" to "壹",
        )
    }
}
