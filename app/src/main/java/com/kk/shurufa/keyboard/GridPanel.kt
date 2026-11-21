package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * A vertically scrolling grid of text cells, in one of several styles:
 *  - SOLID / SOFT: key-like cells in fixed columns (symbols use SOFT);
 *  - RAIL: the category list beside symbols and emoji, with a highlight that glides between them;
 *  - FLAT: the expanded candidate grid; cells span 1..[columns] units by text length, separated by hairlines;
 *  - CARD: two-line clipboard cards with a small tag;
 *  - PLAIN: emoji, with a corner mark on those that come in more forms.
 * Past either end the grid stretches a little and springs back.
 */
@SuppressLint("ViewConstructor")
class GridPanel(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var columns = 5
    var rowHeightDp = 44f
    var textSp = 19f
    var cellStyle = CellStyle.SOLID
    var onItem: ((Int) -> Unit)? = null
    var selectedIndex = -1
        set(v) {
            if (field == v) return
            val from = field
            field = v
            if (from < 0 || v < 0 || !prefs.animations) selPos.snap(v.toFloat()) else { selPos.enabled = true; selPos.animateTo(v.toFloat()) }
            invalidate()
        }
    var marks: Set<Int> = emptySet()
    var tags: List<String> = emptyList()
    /** Icons drawn before RAIL labels (e.g. 查找), aligned with the items. */
    var railIcons: List<String?> = emptyList()
    var highlightFirst = false
    var overlay: PopupOverlay? = null
    var longOptions: ((Int) -> List<Output>)? = null
    /** Like [longOptions], in rows (row 0 nearest the finger). Takes precedence when set. */
    var longOptionRows: ((Int) -> List<List<Output>>)? = null
    var onLongOption: ((Int, Output) -> Unit)? = null
    /** Touch feedback: "press" when a cell is touched, "popup" when its menu opens. */
    var onFeedback: ((String) -> Unit)? = null
    /** Shown centered when there are no items. */
    var emptyText = ""
    /** Small section title drawn above the items (does not scroll). */
    var header = ""
    /** Small notes drawn above FLAT cells (typo marks, readings). */
    var notes: List<String> = emptyList()
    /**
     * When set, a vertical drag that starts between cells (or on the header) is handed over,
     * e.g. to resize the clipboard (phase 0 start, 1 move, 2 end).
     */
    var onGapDrag: ((phase: Int, rawY: Float, velocityY: Float) -> Unit)? = null
    /** Margins around and between key-like cells (default 6 dp). */
    var cellPadDp: Float? = null
    var cellGapDp: Float? = null
    /** Fade the bottom edge while more items are below (a hint to scroll). */
    var scrollHint = false
    /** Fling friction multiplier (higher stops sooner). */
    var friction = 1f
        set(v) { field = v; scroller.setFriction(ViewConfiguration.getScrollFriction() * v) }

    enum class CellStyle { SOLID, SOFT, RAIL, FLAT, CARD, PLAIN }

    private var items: List<String> = emptyList()
    private val cells = mutableListOf<RectF>()
    private var contentH = 0f
    private var scrollPos = 0f
    private val scroller = OverScroller(context)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val appear = Tween(1f)
    private val selPos = Spring(-1f, stiffness = 800f, dampingRatio = 0.85f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tri = Path()
    private val tmp = RectF()
    private val layouts = HashMap<Int, StaticLayout>()
    private var hintShader: LinearGradient? = null

    fun setItems(list: List<String>, resetScroll: Boolean = true) {
        items = list
        layouts.clear()
        if (resetScroll) { scrollPos = 0f; scroller.forceFinished(true) }
        layoutCells()
        scrollPos = scrollPos.coerceIn(0f, maxScroll)
        if (prefs.animations && resetScroll) { appear.set(0f); appear.animateTo(1f, dur(Motion.SWAP + 30), Motion.ENTER) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutCells(); layouts.clear(); hintShader = null }

    override fun onThemeChanged() { layouts.clear(); hintShader = null }

    private fun layoutCells() {
        cells.clear()
        if (width == 0) return
        val flat = cellStyle == CellStyle.FLAT
        val pad = if (flat) 0f else dp(cellPadDp ?: 6f)
        val g = if (flat) 0f else dp(cellGapDp ?: 6f)
        val rh = dp(rowHeightDp)
        tp.textSize = sp(textSp)
        val cw = (width - 2 * pad - g * (columns - 1)) / columns
        if (flat) {
            // A grid of [columns] units. Each word takes as many units as its text needs, rows are
            // filled greedily, and the units left at the end of a row are handed to its widest
            // words, so every row is flush and cell edges line up.
            val unit = width.toFloat() / columns
            val noteP = Paint(tp).apply { textSize = sp(Ui.CAPTION) }
            val need = items.mapIndexed { i, s ->
                val w = max(tp.measureText(s), notes.getOrNull(i)?.let { noteP.measureText(it) } ?: 0f)
                ceil((w + dp(16f)) / unit).toInt().coerceIn(1, columns)
            }
            var i = 0
            var row = 0
            while (i < items.size) {
                var j = i
                var used = 0
                while (j < items.size && used + need[j] <= columns) { used += need[j]; j++ }
                if (j == i) { used = need[i]; j = i + 1 }
                val spans = need.subList(i, j).toMutableList()
                var spare = columns - used
                // stretch only a little: at most one extra unit per cell, longest words first, so short
                // words keep their size and rows stay dense; what is left stays at the end of the row
                if (j < items.size && spare > 0) {
                    val order = spans.indices.sortedByDescending { spans[it] }
                    for (k in order) { if (spare == 0) break; spans[k]++; spare-- }
                }
                var col = 0
                for ((n, _) in (i until j).withIndex()) {
                    cells += RectF(col * unit, row * rh, (col + spans[n]) * unit, (row + 1) * rh)
                    col += spans[n]
                }
                row++
                i = j
            }
        } else {
            items.forEachIndexed { i, _ ->
                val r = i / columns; val c = i % columns
                val x = pad + c * (cw + g); val y = pad + r * (rh + g)
                cells += RectF(x, y, x + cw, y + rh)
            }
        }
        val top = headerH()
        if (top > 0) cells.forEach { it.offset(0f, top) }
        contentH = (cells.maxOfOrNull { it.bottom } ?: 0f) + pad
    }

    private fun headerH() = if (header.isNotEmpty()) dp(22f) else 0f

    private val maxScroll get() = max(0f, contentH - height)

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        var again = false
        if (scroller.computeScrollOffset()) { scrollPos = scroller.currY.toFloat(); again = true }
        val a = appear.value
        if (appear.running) again = true
        c.save()
        c.translate(0f, -scrollPos + (1 - a) * dp(10f))
        if (cellStyle == CellStyle.RAIL) again = drawRailHighlight(c, a) || again
        for (i in items.indices) {
            val r = cells.getOrNull(i) ?: break
            if (r.bottom < scrollPos || r.top > scrollPos + height) continue
            val s = items[i]
            when (cellStyle) {
                CellStyle.SOLID, CellStyle.SOFT -> drawKeyCell(c, i, s, r, a)
                CellStyle.RAIL -> drawRailCell(c, i, s, r, a)
                CellStyle.FLAT -> drawFlatCell(c, i, s, r, a)
                CellStyle.CARD -> drawCard(c, i, s, r, a)
                CellStyle.PLAIN -> drawPlainCell(c, i, s, r, a)
            }
        }
        c.restore()
        if (scrollHint && scrollPos < maxScroll - 1f) {
            val h = dp(18f)
            val sh = hintShader ?: LinearGradient(0f, height - h, 0f, height.toFloat(), Colors.alpha(t.background, 0), t.background, Shader.TileMode.CLAMP)
                .also { hintShader = it }
            bg.shader = sh
            c.drawRect(0f, height - h, width.toFloat(), height.toFloat(), bg)
            bg.shader = null
        }
        if (header.isNotEmpty()) {
            bg.color = t.background
            c.drawRect(0f, 0f, width.toFloat(), headerH(), bg)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.color = t.hint; tp.typeface = Ui.medium
            c.drawText(header, dp(Ui.EDGE + 2f), dp(15f), tp)
            tp.typeface = Ui.regular; tp.textAlign = Paint.Align.CENTER
        }
        if (items.isEmpty() && emptyText.isNotEmpty()) {
            tp.textSize = sp(Ui.BODY); tp.color = t.hint; tp.alpha = (255 * a).toInt()
            drawText(c, emptyText, width / 2f, (headerH() + height) / 2f, tp)
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun label(c: Canvas, s: String, r: RectF, size: Float, color: Int, alpha: Float, bold: Boolean = false, dx: Float = 0f) {
        tp.textSize = size
        tp.color = color
        tp.alpha = (255 * alpha).toInt()
        tp.typeface = if (bold) Ui.medium else Ui.regular
        val maxW = r.width() - dp(10f)
        val tw = tp.measureText(s)
        if (tw > maxW) tp.textSize = size * maxW / tw
        drawText(c, s, r.centerX() + dx, r.centerY(), tp)
        tp.typeface = Ui.regular
    }

    private fun drawKeyCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        val solid = cellStyle == CellStyle.SOLID
        val rad = dp(prefs.keyRadiusDp)
        bg.color = if (solid) (if (i == pressed) theme.surfacePressed else theme.surface) else (if (i == pressed) theme.softPressed else theme.soft)
        bg.color = Colors.fade(bg.color, a)
        c.drawRoundRect(r, rad, rad, bg)
        if (i in marks) {
            bg.color = theme.accent
            c.drawCircle(r.right - dp(6f), r.top + dp(6f), dp(2.2f), bg)
        }
        label(c, s, r, sp(textSp), theme.ink, a)
    }

    /** The rail's highlight, gliding to the selected category. True while it moves. */
    private fun drawRailHighlight(c: Canvas, a: Float): Boolean {
        if (selectedIndex !in items.indices || cells.isEmpty()) return false
        if (selPos.running) selPos.step()
        val p = selPos.value.coerceIn(0f, (cells.size - 1).toFloat())
        val i0 = p.toInt(); val i1 = minOf(i0 + 1, cells.size - 1); val f = p - i0
        val r0 = cells[i0]; val r1 = cells[i1]
        val top = r0.top + (r1.top - r0.top) * f
        val bottom = r0.bottom + (r1.bottom - r0.bottom) * f
        val rad = dp(prefs.keyRadiusDp)
        bg.color = Colors.fade(theme.accentSoft, a)
        c.drawRoundRect(r0.left + dp(4f), top + dp(2f), r0.right - dp(4f), bottom - dp(2f), rad, rad, bg)
        return selPos.running
    }

    private fun drawRailCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        val sel = i == selectedIndex
        if (i == pressed && !sel) {
            val rad = dp(prefs.keyRadiusDp)
            bg.color = theme.soft
            c.drawRoundRect(r.left + dp(4f), r.top + dp(2f), r.right - dp(4f), r.bottom - dp(2f), rad, rad, bg)
        }
        val color = if (sel) theme.accent else theme.inkSoft
        val icon = railIcons.getOrNull(i)
        if (icon != null) {
            tp.textSize = sp(textSp); tp.typeface = if (sel) Ui.medium else Ui.regular
            val iconPx = dp(15f)
            val w = iconPx + dp(3f) + tp.measureText(s)
            ip.color = Colors.fade(color, a)
            Icons.draw(c, icon, r.centerX() - w / 2 + iconPx / 2, r.centerY(), iconPx, ip)
            label(c, s, r, sp(textSp), color, a, bold = sel, dx = (iconPx + dp(3f)) / 2)
        } else label(c, s, r, sp(textSp), color, a, bold = sel)
    }

    private fun drawFlatCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        if (i == pressed) { bg.color = theme.soft; c.drawRect(r, bg) }
        bg.color = theme.divider
        c.drawRect(r.left + dp(6f), r.bottom - dp(0.7f), r.right - dp(6f), r.bottom, bg)
        if (r.right < width - 1) c.drawRect(r.right - dp(0.7f), r.top + dp(14f), r.right, r.bottom - dp(14f), bg)
        val note = notes.getOrNull(i).orEmpty()
        val color = if (highlightFirst && i == 0) theme.accent else theme.ink
        if (note.isNotEmpty()) {
            tp.textSize = sp(Ui.CAPTION); tp.color = if (note.contains('→') || note.contains('⇄')) theme.accent else theme.hint
            tp.alpha = (255 * a).toInt()
            c.drawText(note, r.centerX(), r.top + dp(12f), tp)
            label(c, s, tmp.apply { set(r.left, r.top + dp(6f), r.right, r.bottom) }, sp(textSp), color, a)
        } else label(c, s, r, sp(textSp), color, a)
    }

    private fun drawPlainCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        if (i == pressed) {
            val rad = dp(prefs.keyRadiusDp)
            bg.color = theme.soft
            c.drawRoundRect(r, rad, rad, bg)
        }
        label(c, s, r, sp(textSp), theme.ink, a)
        if (i in marks) {
            // more forms on long-press (skin tones): a small, quiet corner triangle
            val m = dp(4f); val x = r.right - dp(5f); val y = r.bottom - dp(5f)
            bg.color = Colors.fade(theme.hint, 0.55f * a)
            tri.reset(); tri.moveTo(x, y - m); tri.lineTo(x, y); tri.lineTo(x - m, y); tri.close()
            c.drawPath(tri, bg)
        }
    }

    private fun drawCard(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        bg.color = Colors.fade(if (i == pressed) theme.surfacePressed else theme.surface, a)
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        c.drawRoundRect(r, rad, rad, bg)
        val tag = tags.getOrNull(i).orEmpty()
        var top = r.top + dp(10f)
        val tagged = tag.isNotEmpty() || i in marks
        if (tagged) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = sp(Ui.CAPTION)
            tp.color = theme.accent
            tp.alpha = (255 * a).toInt()
            tp.typeface = Ui.medium
            c.drawText(if (i in marks) "已固定" + (if (tag.isNotEmpty()) " · $tag" else "") else tag, r.left + dp(12f), top + sp(Ui.CAPTION), tp)
            tp.typeface = Ui.regular
            tp.textAlign = Paint.Align.CENTER
            top += sp(Ui.TITLE)
        }
        val w = (r.width() - dp(24f)).toInt().coerceAtLeast(1)
        val maxLines = if (tagged) 2 else 3
        val lay = layouts.getOrPut(i) {
            cardText.textSize = sp(textSp)
            cardText.color = theme.ink
            StaticLayout.Builder.obtain(s, 0, s.length, cardText, w)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).build()
        }
        c.save()
        c.translate(r.left + dp(12f), top)
        lay.paint.alpha = (255 * a).toInt()
        lay.draw(c)
        c.restore()
    }

    // ---- touch ----
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var rawScroll = 0f
    private var dragging = false
    private var pressed = -1
    private var menuFor = -1
    private var gapDrag = false
    private var downAt = 0L

    private val longRunnable = Runnable {
        val i = pressed
        if (i < 0 || dragging) return@Runnable
        val rows = longOptionRows?.invoke(i)?.filter { it.isNotEmpty() }
        val opts = longOptions?.invoke(i).orEmpty()
        if (rows.isNullOrEmpty() && opts.isEmpty()) return@Runnable
        locateIn(overlay)
        val r = RectF(cells[i]).apply { offset(layerOffset[0], -scrollPos + layerOffset[1]) }
        if (!rows.isNullOrEmpty()) overlay?.showOptionRows(r, dp(44f), rows, downX + layerOffset[0])
        else overlay?.showOptions(r, dp(44f), opts, downX + layerOffset[0])
        menuFor = i
        onFeedback?.invoke("popup")
        invalidate()
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in cells.indices) if (cells[i].contains(x, y + scrollPos)) return i
        return -1
    }

    /** Past either end the content follows the finger with growing resistance. */
    private fun rubber(over: Float): Float {
        val limit = dp(80f)
        return limit * (1f - 1f / (abs(over) / limit * 0.55f + 1f)) * if (over < 0) -1 else 1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y; dragging = false; menuFor = -1; gapDrag = false
                rawScroll = scrollPos
                downAt = SystemClock.uptimeMillis()
                pressed = hit(e.x, e.y)
                if (pressed >= 0) onFeedback?.invoke("press")
                if (pressed >= 0 && (longOptions != null || longOptionRows != null)) handler.postDelayed(longRunnable, 420)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuFor >= 0) {
                    locateIn(overlay)
                    overlay?.moveSelection(e.x + layerOffset[0], e.y + layerOffset[1])
                    return true
                }
                if (gapDrag) { onGapDrag?.invoke(1, e.rawY, 0f); return true }
                if (!dragging && pressed < 0 && onGapDrag != null && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                    gapDrag = true; onGapDrag?.invoke(0, e.rawY - (e.y - downY), 0f); onGapDrag?.invoke(1, e.rawY, 0f); return true
                }
                // any real movement means it was not a tap
                if (!dragging && pressed >= 0 && abs(e.x - downX) > slop) { pressed = -1; handler.removeCallbacks(longRunnable); invalidate() }
                if (!dragging && abs(e.y - downY) > slop) { dragging = true; pressed = -1; handler.removeCallbacks(longRunnable); invalidate() }
                if (dragging) {
                    rawScroll -= e.y - lastY
                    val m = maxScroll
                    scrollPos = when {
                        rawScroll < 0f -> rubber(rawScroll)
                        rawScroll > m -> m + rubber(rawScroll - m)
                        else -> rawScroll
                    }
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                if (gapDrag) {
                    vt?.computeCurrentVelocity(1000)
                    onGapDrag?.invoke(2, e.rawY, vt?.yVelocity ?: 0f)
                    gapDrag = false; vt?.recycle(); vt = null
                    return true
                }
                val held = SystemClock.uptimeMillis() - downAt
                when {
                    menuFor >= 0 -> {
                        val o = overlay
                        val idx = menuFor
                        if (o == null || o.pickedByDrag()) {
                            o?.selected()?.let { onLongOption?.invoke(idx, it) }
                            o?.hideOptions()
                        } else o.stick { pick -> pick?.let { onLongOption?.invoke(idx, it) } }   // stays open for a tap
                        menuFor = -1
                    }
                    dragging -> {
                        vt?.computeCurrentVelocity(1000)
                        val m = maxScroll.toInt()
                        if (scrollPos < 0f || scrollPos > m) scroller.springBack(0, scrollPos.toInt(), 0, 0, 0, m)
                        else scroller.fling(0, scrollPos.toInt(), 0, -(vt?.yVelocity ?: 0f).toInt(), 0, 0, 0, m, 0, dp(28f).toInt())
                        postInvalidateOnAnimation()
                    }
                    // held far longer than a tap (with no menu to open): a change of mind
                    pressed >= 0 && (prefs.holdCancelMs <= 0 || held < prefs.holdCancelMs) -> onItem?.invoke(pressed)
                }
                pressed = -1
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (gapDrag) { onGapDrag?.invoke(2, e.rawY, 0f); gapDrag = false }
                if (menuFor >= 0) { overlay?.hideOptions(); menuFor = -1 }
                if (scrollPos < 0f || scrollPos > maxScroll) { scroller.springBack(0, scrollPos.toInt(), 0, 0, 0, maxScroll.toInt()); postInvalidateOnAnimation() }
                pressed = -1; vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
