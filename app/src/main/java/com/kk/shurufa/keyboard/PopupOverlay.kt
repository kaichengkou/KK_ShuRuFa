package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.kk.shurufa.Prefs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent layer above the whole input view. Draws the key preview bubble and the
 * long-press option popup, both animated.
 */
@SuppressLint("ViewConstructor")
class PopupOverlay(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    /** Called when the selection moves to another option (for haptics). */
    var onTick: (() -> Unit)? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dur(ms: Long) = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
    }

    // Never ask for height of our own: the input view must be as tall as the keys, not the screen.
    // The parent FrameLayout then stretches this layer to its final size in a second pass.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    // ---- preview bubble ----
    private var pvRect = RectF()
    private var pvLabel = ""
    private var pvOld: String? = null
    private var pvDir: Dir? = null
    private var pvMath = false
    private val pvShow = Tween(0f)
    private val pvSwap = Tween(1f)

    fun showPreview(r: RectF, label: String, dir: Dir?, math: Boolean) {
        val wasVisible = pvShow.target > 0.5f
        if (wasVisible && label != pvLabel) {
            pvOld = pvLabel
            pvSwap.set(0f)
            pvSwap.animateTo(1f, dur(110))
        } else if (!wasVisible) {
            pvOld = null
            pvSwap.set(1f)
            pvShow.set(0f)
        }
        pvRect = r
        pvLabel = label
        pvDir = dir
        pvMath = math
        pvShow.animateTo(1f, dur(110), Tween.POP)
        invalidate()
    }

    fun hidePreview() {
        if (pvShow.target == 0f) return
        pvShow.animateTo(0f, dur(90))
        invalidate()
    }

    // ---- long-press options ----
    // Options come in rows; row 0 is right above the key. On the left half of the keyboard a row
    // runs rightwards from the key, on the right half it runs leftwards, so the finger always
    // slides inwards. The first option of row 0 starts selected (it is the up-flick character).
    //
    // Two looks, one type size each: characters (keys, emoji) sit in equal cells at one glyph
    // size, widened to fit the widest instead of shrinking some; commands and longer text
    // (取消 · 收藏 · 删除…) are pills as wide as their words, all set at body size.
    private var rows: List<List<Output>> = emptyList()
    private var cells: List<List<RectF>> = emptyList()
    private var opRect = RectF()
    private var cellH = 0f
    private var minCellW = 0f
    private var mirror = false
    private var words = false
    private var fontPx = 0f
    private var selRow = 0
    private var selCol = 0
    private var keyBottom = 0f
    private val opShow = Tween(0f)
    private val selX = Tween(0f)
    private val selY = Tween(0f)
    private val selW = Tween(0f)
    private val pad get() = dp(5f)

    fun showOptions(key: RectF, keyH: Float, options: List<Output>, fingerX: Float, math: Boolean, fingerY: Float? = null) {
        showOptionRows(key, keyH, listOf(options), fingerX, fingerY)
    }

    /** [fingerY]: where the finger really is, when the menu is not anchored on it (the candidate bar). */
    fun showOptionRows(key: RectF, keyH: Float, options: List<List<Output>>, fingerX: Float, fingerY: Float? = null) {
        val all = options.flatten()
        if (all.isEmpty()) return
        words = all.any { it.text.startsWith("§") || glyphs(it.label) > 3 }
        cellH = keyH * 1.02f
        mirror = key.centerX() > width / 2f
        val avail = width - 2 * pad - dp(8f)
        val widths: List<List<Float>>
        if (words) {
            fontPx = sp(Ui.BODY); text.textSize = fontPx; text.typeface = Ui.regular
            val padX = dp(15f)
            // wrap each given row into lines that fit the screen
            val lines = mutableListOf<List<Output>>(); val lw = mutableListOf<List<Float>>()
            for (row in options) {
                var line = mutableListOf<Output>(); var ws = mutableListOf<Float>(); var used = 0f
                for (o in row) {
                    val w = (text.measureText(o.label) + 2 * padX).coerceIn(dp(56f), avail)
                    if (line.isNotEmpty() && used + w > avail) { lines += line; lw += ws; line = mutableListOf(); ws = mutableListOf(); used = 0f }
                    line += o; ws += w; used += w
                }
                if (line.isNotEmpty()) { lines += line; lw += ws }
            }
            rows = lines; widths = lw
        } else {
            fontPx = sp(22f); text.textSize = fontPx; text.typeface = Ui.regular
            val widest = all.maxOf { text.measureText(it.label) }
            var cw = max(max(key.width() * 0.92f, dp(40f)), widest + dp(16f))
            val per0 = floor(avail / cw).toInt()
            if (per0 < 1) cw = avail
            if (widest + dp(16f) > cw) fontPx *= (cw - dp(16f)) / widest
            val per = max(1, floor(avail / cw).toInt())
            rows = options.flatMap { it.chunked(per) }.filter { it.isNotEmpty() }
            widths = rows.map { r -> r.map { cw } }
        }
        minCellW = widths.flatten().min()
        val inner = widths.maxOf { it.sum() }
        val w = inner + 2 * pad
        val h = rows.size * cellH + 2 * pad
        val first = widths[0][0]
        var left = if (mirror) key.centerX() + first / 2 + pad - w else key.centerX() - first / 2 - pad
        left = left.coerceIn(dp(4f), width - w - dp(4f))
        val top = max(dp(2f), key.top - h - dp(6f))
        opRect = RectF(left, top, left + w, top + h)
        // lay out the cells: each line starts at the side nearest the key
        cells = rows.indices.map { r ->
            val y = opRect.bottom - pad - (r + 1) * cellH
            var x = if (mirror) opRect.right - pad else opRect.left + pad
            widths[r].map { cw ->
                val rect = if (mirror) RectF(x - cw, y, x, y + cellH) else RectF(x, y, x + cw, y + cellH)
                x += if (mirror) -cw else cw
                rect
            }
        }
        keyBottom = key.bottom
        selRow = 0; selCol = 0
        startX = fingerX
        startY = fingerY ?: key.centerY()
        lastX = fingerX; lastY = startY
        moved = false
        cells[0][0].let { selX.set(it.left); selY.set(it.top); selW.set(it.width()) }
        opShow.set(0f)
        opShow.animateTo(1f, dur(150), Tween.EASE_OUT)
        invalidate()
    }

    private var startX = 0f
    private var startY = 0f
    private var moved = false

    private var lastX = 0f
    private var lastY = 0f

    /** The option in line [r] under [x], or the nearest end of the line. */
    private fun colAt(r: Int, x: Float): Int {
        val line = cells[r]
        line.indexOfFirst { x >= it.left && x < it.right }.let { if (it >= 0) return it }
        val leftmost = line.indices.minBy { line[it].left }
        val rightmost = line.indices.maxBy { line[it].right }
        return if (x < line[leftmost].left) leftmost else rightmost
    }

    private fun moveSel(r: Int, c: Int, animate: Boolean) {
        val rect = cells[r][c]
        if (animate) { selX.animateTo(rect.left, dur(110)); selY.animateTo(rect.top, dur(110)); selW.animateTo(rect.width(), dur(110)) }
        else { selX.set(rect.left); selY.set(rect.top); selW.set(rect.width()) }
    }

    fun moveSelection(x: Float, y: Float) {
        if (rows.isEmpty()) return
        lastX = x; lastY = y
        if (!moved) {
            if (kotlin.math.abs(x - startX) < minCellW * 0.6f && kotlin.math.abs(y - startY) < cellH * 0.6f) return
            moved = true
        }
        var nr = -1; var nc = -1
        if (y <= keyBottom + cellH * 1.2f) {
            nr = floor((opRect.bottom - pad - y) / cellH).toInt().coerceIn(0, rows.size - 1)
            nc = colAt(nr, x)
        }
        if (nr != selRow || nc != selCol) {
            selRow = nr; selCol = nc
            if (nr >= 0) { moveSel(nr, nc, true); onTick?.invoke() }
            invalidate()
        }
    }

    /** Visible characters: an emoji sequence (👮🏽‍♀️) counts as one. */
    private fun glyphs(s: String): Int {
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        var n = 0
        while (it.next() != android.icu.text.BreakIterator.DONE) n++
        return n
    }

    fun selected(): Output? = if (selRow < 0) null else rows.getOrNull(selRow)?.getOrNull(selCol)

    // ---- sticky menus: after a long-press on the clipboard, candidates or emoji the menu stays
    // open once the finger lifts, and is used with ordinary taps (a tap elsewhere closes it) ----
    var sticky = false
        private set
    private var onChoose: ((Output?) -> Unit)? = null
    /** Told when a sticky menu opens or closes (the window must then accept taps anywhere). */
    var onStickyChanged: ((Boolean) -> Unit)? = null

    /** True when the finger slid onto an option before lifting (classic drag-to-pick). */
    fun pickedByDrag() = moved && selRow >= 0 && RectF(opRect).apply { inset(-dp(10f), -dp(10f)) }.contains(lastX, lastY)

    fun stick(choose: (Output?) -> Unit) {
        sticky = true; onChoose = choose
        selRow = -1
        onStickyChanged?.invoke(true)
        invalidate()
    }

    private fun optionAt(x: Float, y: Float): Pair<Int, Int>? {
        if (!opRect.contains(x, y)) return null
        cells.forEachIndexed { r, line -> line.forEachIndexed { c, rect -> if (rect.contains(x, y)) return r to c } }
        return null
    }

    /** Closes an open menu without a choice (the panel changed under it). */
    fun dismissSticky() { if (sticky) closeSticky(null) }

    private fun closeSticky(pick: Output?) {
        val cb = onChoose
        sticky = false; onChoose = null
        hideOptions()
        onStickyChanged?.invoke(false)
        cb?.invoke(pick)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!sticky) return false
        val hit = optionAt(e.x, e.y)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                val (r, c) = hit ?: (-1 to -1)
                if (r != selRow || c != selCol) {
                    selRow = r; selCol = c
                    if (r >= 0) { moveSel(r, c, false); onTick?.invoke() }
                    invalidate()
                }
            }
            android.view.MotionEvent.ACTION_UP -> closeSticky(hit?.let { (r, c) -> rows[r][c] })
            android.view.MotionEvent.ACTION_CANCEL -> closeSticky(null)
        }
        return true
    }

    fun hideOptions() {
        opShow.animateTo(0f, dur(90))
        invalidate()
    }

    // ---- drawing ----
    override fun onDraw(c: Canvas) {
        var again = false
        val ps = pvShow.value
        if (ps > 0.01f && opShow.value < 0.5f) {
            again = again || pvShow.running || pvSwap.running
            drawPreview(c, ps)
        }
        val os = opShow.value
        if (os > 0.01f && rows.isNotEmpty()) {
            again = again || opShow.running || selX.running || selY.running || selW.running
            drawOptions(c, os)
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun bubble(c: Canvas, r: RectF, alpha: Float) {
        bg.color = theme.popup
        bg.alpha = (255 * alpha).toInt()
        bg.setShadowLayer(dp(6f), 0f, dp(2f), (0x40 * alpha).toInt() shl 24)
        c.drawRoundRect(r, Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)), Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)), bg)
        bg.clearShadowLayer()
    }

    private fun drawPreview(c: Canvas, show: Float) {
        c.save()
        val s = 0.85f + 0.15f * show
        c.scale(s, s, pvRect.centerX(), pvRect.bottom)
        val a = show.coerceIn(0f, 1f)
        bubble(c, pvRect, a)
        text.typeface = Ui.regular
        val p = pvSwap.value
        val slide = dp(10f)
        fun offs(d: Dir?, k: Float) = when (d) {
            Dir.UP -> 0f to -k; Dir.DOWN -> 0f to k; Dir.LEFT -> -k to 0f; Dir.RIGHT -> k to 0f; null -> 0f to 0f
        }
        c.save(); c.clipRect(pvRect)
        pvOld?.let { old ->
            if (p < 1f) {
                val (ox, oy) = offs(pvDir, slide * p)
                drawLabel(c, old, pvRect.centerX() - ox, pvRect.centerY() - oy, (1 - p) * a, false)
            }
        }
        val (nx, ny) = offs(pvDir, slide * (1 - p))
        drawLabel(c, pvLabel, pvRect.centerX() + nx, pvRect.centerY() + ny, p * a, pvDir != null)
        c.restore()
        c.restore()
    }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float, alpha: Float, accent: Boolean) {
        text.color = if (accent) theme.accent else theme.ink
        text.alpha = (255 * alpha).toInt()
        text.textSize = sp(if (s.codePointCount(0, s.length) <= 2) 26f else 17f)
        val maxW = pvRect.width() - dp(8f)
        val tw = text.measureText(s)
        if (tw > maxW) text.textSize *= maxW / tw
        val fm = text.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawOptions(c: Canvas, show: Float) {
        c.save()
        val s = 0.9f + 0.1f * show
        c.scale(s, s, opRect.centerX(), opRect.bottom)
        bubble(c, opRect, show)
        val alpha = (255 * show).toInt()
        val inset = dp(1.5f)
        val rad = if (words) (cellH - 2 * inset) / 2 else dp(prefs.keyRadiusDp)
        // hairlines between word pills, except beside the highlighted one
        if (words) {
            bg.color = theme.divider; bg.alpha = (Color.alpha(theme.divider) * 0.55f * show).toInt()
            cells.forEachIndexed { r, line ->
                for (i in 1 until line.size) {
                    if (r == selRow && (i == selCol || i - 1 == selCol)) continue
                    val a = line[i - 1]; val b = line[i]
                    val x = if (mirror) a.left else a.right
                    c.drawRect(x - dp(0.4f), b.top + cellH * 0.36f, x + dp(0.4f), b.bottom - cellH * 0.36f, bg)
                }
            }
        }
        if (selRow >= 0) {
            bg.color = theme.accent; bg.alpha = alpha
            val x = selX.value; val y = selY.value
            c.drawRoundRect(x + inset, y + inset, x + selW.value - inset, y + cellH - inset, rad, rad, bg)
        }
        text.typeface = Ui.regular
        text.textSize = fontPx
        val fm = text.fontMetrics
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, o ->
                val rect = cells[r][i]
                val on = r == selRow && i == selCol
                // 取消 is the way out, not a choice: quieter
                text.color = when { on -> theme.onAccent; o.text == "§cancel" -> theme.inkSoft; else -> theme.ink }
                text.alpha = alpha
                c.drawText(o.label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
        }
        c.restore()
    }
}
