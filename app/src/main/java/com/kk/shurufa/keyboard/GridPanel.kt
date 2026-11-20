package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * A vertically scrolling grid of text cells, in one of several styles:
 *  - SOLID / SOFT: key-like cells in fixed columns (symbols use SOFT);
 *  - RAIL: the category list beside the symbols;
 *  - FLAT: the expanded candidate grid; cells span 1..[columns] units by text length, separated by hairlines;
 *  - CARD: two-line clipboard cards with a small tag.
 */
@SuppressLint("ViewConstructor")
class GridPanel(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var columns = 5
    var rowHeightDp = 44f
    var textSp = 19f
    var cellStyle = CellStyle.SOLID
    var onItem: ((Int) -> Unit)? = null
    var selectedIndex = -1
    var marks: Set<Int> = emptySet()
    var tags: List<String> = emptyList()
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
        set(v) { field = v; scroller.setFriction(android.view.ViewConfiguration.getScrollFriction() * v) }

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

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cardText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tri = android.graphics.Path()
    private val layouts = HashMap<Int, StaticLayout>()

    fun setItems(list: List<String>, resetScroll: Boolean = true) {
        items = list
        layouts.clear()
        if (resetScroll) { scrollPos = 0f; scroller.forceFinished(true) }
        layoutCells()
        if (prefs.animations && resetScroll) { appear.set(0f); appear.animateTo(1f, (170 * prefs.animSpeed).toLong()) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutCells(); layouts.clear() }

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
            // A grid of [columns] units. Each word takes as many units as its text needs (two at
            // least for words), rows are filled greedily, and the units left at the end of a row
            // are handed to its widest words, so every row is flush and cell edges line up.
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
                for ((n, idx) in (i until j).withIndex()) {
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

    private fun headerH() = if (header.isNotEmpty()) dp(20f) else 0f

    private val maxScroll get() = max(0f, contentH - height)

    override fun onDraw(c: Canvas) {
        c.drawColor(if (cellStyle == CellStyle.RAIL) blendRail() else theme.background)
        if (scroller.computeScrollOffset()) { scrollPos = scroller.currY.toFloat(); postInvalidateOnAnimation() }
        val a = appear.value
        if (appear.running) postInvalidateOnAnimation()
        c.save()
        c.translate(0f, -scrollPos + (1 - a) * dp(10f))
        items.forEachIndexed { i, s ->
            val r = cells.getOrNull(i) ?: return@forEachIndexed
            if (r.bottom < scrollPos || r.top > scrollPos + height) return@forEachIndexed
            when (cellStyle) {
                CellStyle.SOLID, CellStyle.SOFT -> drawKeyCell(c, i, s, r, a)
                CellStyle.RAIL -> drawRailCell(c, i, s, r, a)
                CellStyle.FLAT -> drawFlatCell(c, i, s, r, a)
                CellStyle.CARD -> drawCard(c, i, s, r, a)
                CellStyle.PLAIN -> {
                    if (i == pressed) { bg.color = theme.soft; c.drawRoundRect(r, dp(prefs.keyRadiusDp), dp(prefs.keyRadiusDp), bg) }
                    label(c, s, r, sp(textSp), theme.ink, a)
                    if (i in marks) {
                        // more forms on long-press (skin tones): a small corner triangle
                        val m = dp(5f); val x = r.right - dp(4f); val y = r.bottom - dp(4f)
                        bg.color = theme.hint; bg.alpha = (170 * a).toInt()
                        tri.reset(); tri.moveTo(x, y - m); tri.lineTo(x, y); tri.lineTo(x - m, y); tri.close()
                        c.drawPath(tri, bg)
                    }
                }
            }
        }
        c.restore()
        if (scrollHint && scrollPos < maxScroll - 1f) {
            val h = dp(18f)
            bg.shader = android.graphics.LinearGradient(0f, height - h, 0f, height.toFloat(), theme.background and 0x00FFFFFF, theme.background, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, height - h, width.toFloat(), height.toFloat(), bg)
            bg.shader = null
        }
        if (header.isNotEmpty()) {
            bg.color = theme.background
            c.drawRect(0f, 0f, width.toFloat(), headerH(), bg)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.color = theme.hint; tp.typeface = Ui.medium
            c.drawText(header, dp(12f), dp(14f), tp)
            tp.typeface = Ui.regular; tp.textAlign = Paint.Align.CENTER
        }
        if (items.isEmpty() && emptyText.isNotEmpty()) {
            tp.textSize = sp(Ui.BODY); tp.color = theme.hint
            val fm = tp.fontMetrics
            c.drawText(emptyText, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, tp)
        }
    }

    private fun blendRail(): Int = theme.background

    private fun label(c: Canvas, s: String, r: RectF, size: Float, color: Int, alpha: Float, bold: Boolean = false) {
        tp.textSize = size
        tp.color = color
        tp.alpha = (255 * alpha).toInt()
        tp.typeface = if (bold) Ui.medium else Ui.regular
        val maxW = r.width() - dp(10f)
        val tw = tp.measureText(s)
        if (tw > maxW) tp.textSize = size * maxW / tw
        val fm = tp.fontMetrics
        c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
        tp.typeface = Ui.regular
    }

    private fun drawKeyCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        val solid = cellStyle == CellStyle.SOLID
        bg.color = if (solid) (if (i == pressed) theme.surfacePressed else theme.surface) else (if (i == pressed) theme.softPressed else theme.soft)
        if (solid) bg.alpha = (255 * a).toInt()
        c.drawRoundRect(r, dp(prefs.keyRadiusDp), dp(prefs.keyRadiusDp), bg)
        if (i in marks) {
            bg.color = theme.accent
            c.drawCircle(r.right - dp(6f), r.top + dp(6f), dp(2.2f), bg)
        }
        label(c, s, r, sp(textSp), theme.ink, a)
    }

    private fun drawRailCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        val sel = i == selectedIndex
        if (sel) {
            bg.color = theme.accentSoft
            c.drawRoundRect(r.left, r.top + dp(1f), r.right, r.bottom - dp(1f), dp(prefs.keyRadiusDp), dp(prefs.keyRadiusDp), bg)
        }
        label(c, s, r, sp(textSp), if (sel) theme.accent else theme.inkSoft, a, bold = sel)
    }

    private fun drawFlatCell(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        if (i == pressed) { bg.color = theme.soft; c.drawRect(r, bg) }
        bg.color = theme.divider
        c.drawRect(r.left + dp(6f), r.bottom - dp(0.7f), r.right - dp(6f), r.bottom, bg)
        if (r.right < width - 1) c.drawRect(r.right - dp(0.7f), r.top + dp(14f), r.right, r.bottom - dp(14f), bg)
        val note = notes.getOrNull(i).orEmpty()
        if (note.isNotEmpty()) {
            tp.textSize = sp(Ui.CAPTION); tp.color = if (note.contains('→') || note.contains('⇄')) theme.accent else theme.hint
            tp.alpha = (255 * a).toInt()
            c.drawText(note, r.centerX(), r.top + dp(12f), tp)
            label(c, s, RectF(r.left, r.top + dp(6f), r.right, r.bottom), sp(textSp), if (highlightFirst && i == 0) theme.accent else theme.ink, a)
        } else label(c, s, r, sp(textSp), if (highlightFirst && i == 0) theme.accent else theme.ink, a)
    }

    private fun drawCard(c: Canvas, i: Int, s: String, r: RectF, a: Float) {
        bg.color = if (i == pressed) theme.surfacePressed else theme.surface
        bg.alpha = (255 * a).toInt()
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        c.drawRoundRect(r, rad, rad, bg)
        val tag = tags.getOrNull(i).orEmpty()
        var top = r.top + dp(10f)
        if (tag.isNotEmpty() || i in marks) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = sp(Ui.CAPTION)
            tp.color = theme.accent
            tp.typeface = Ui.medium
            c.drawText(if (i in marks) "已固定" + (if (tag.isNotEmpty()) " · $tag" else "") else tag, r.left + dp(12f), top + sp(Ui.CAPTION), tp)
            tp.typeface = Ui.regular
            tp.textAlign = Paint.Align.CENTER
            top += sp(Ui.TITLE)
        }
        val w = (r.width() - dp(24f)).toInt().coerceAtLeast(1)
        val maxLines = if (tag.isNotEmpty() || i in marks) 2 else 3
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
    private var dragging = false
    private var pressed = -1
    private var menuFor = -1
    private var gapDrag = false
    private var downAt = 0L

    private fun overlayOffset(): Pair<Float, Float> {
        val o = overlay ?: return 0f to 0f
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); o.getLocationInWindow(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    private val longRunnable = Runnable {
        val i = pressed
        if (i < 0 || dragging) return@Runnable
        val rows = longOptionRows?.invoke(i)?.filter { it.isNotEmpty() }
        val opts = longOptions?.invoke(i).orEmpty()
        if (rows.isNullOrEmpty() && opts.isEmpty()) return@Runnable
        val r = RectF(cells[i]).apply { offset(0f, -scrollPos) }
        val (ox, oy) = overlayOffset()
        r.offset(ox, oy)
        if (!rows.isNullOrEmpty()) overlay?.showOptionRows(r, dp(44f), rows, downX + ox)
        else overlay?.showOptions(r, dp(44f), opts, downX + ox, false)
        menuFor = i
        onFeedback?.invoke("popup")
        invalidate()
    }

    private fun hit(x: Float, y: Float): Int = cells.indexOfFirst { it.contains(x, y + scrollPos) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y; dragging = false; menuFor = -1; gapDrag = false
                downAt = android.os.SystemClock.uptimeMillis()
                pressed = hit(e.x, e.y)
                if (pressed >= 0) onFeedback?.invoke("press")
                if (pressed >= 0 && (longOptions != null || longOptionRows != null)) handler.postDelayed(longRunnable, 420)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuFor >= 0) {
                    val (ox, oy) = overlayOffset()
                    overlay?.moveSelection(e.x + ox, e.y + oy)
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
                    scrollPos = (scrollPos - (e.y - lastY)).coerceIn(0f, maxScroll)
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
                val held = android.os.SystemClock.uptimeMillis() - downAt
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
                        scroller.fling(0, scrollPos.toInt(), 0, -(vt?.yVelocity ?: 0f).toInt(), 0, 0, 0, maxScroll.toInt())
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
                pressed = -1; vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }
}
