package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
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
    private var rows: List<List<Output>> = emptyList()
    private var opRect = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private var perRow = 1
    private var mirror = false
    private var selRow = 0
    private var selCol = 0
    private var keyBottom = 0f
    private val opShow = Tween(0f)
    private val selX = Tween(0f)
    private val selY = Tween(0f)
    private val pad get() = dp(5f)

    fun showOptions(key: RectF, keyH: Float, options: List<Output>, fingerX: Float, math: Boolean, fingerY: Float? = null) {
        val usable = ((width - 2 * dp(5f) - dp(8f)) / max(key.width() * 0.92f, dp(40f))).toInt().coerceAtLeast(1)
        showOptionRows(key, keyH, options.chunked(min(8, usable)), fingerX, fingerY)
    }

    /** [fingerY]: where the finger really is, when the menu is not anchored on it (the candidate bar). */
    fun showOptionRows(key: RectF, keyH: Float, options: List<List<Output>>, fingerX: Float, fingerY: Float? = null) {
        cellW = max(key.width() * 0.92f, dp(40f))
        cellH = keyH * 1.02f
        val maxPer = floor((width - 2 * pad - dp(8f)) / cellW).toInt().coerceAtLeast(1)
        rows = options.flatMap { it.chunked(maxPer) }.filter { it.isNotEmpty() }
        perRow = rows.maxOf { it.size }
        mirror = key.centerX() > width / 2f
        val w = perRow * cellW + 2 * pad
        val h = rows.size * cellH + 2 * pad
        var left = if (mirror) key.centerX() + cellW / 2 + pad - w else key.centerX() - cellW / 2 - pad
        left = left.coerceIn(dp(4f), width - w - dp(4f))
        val top = max(dp(2f), key.top - h - dp(6f))
        opRect = RectF(left, top, left + w, top + h)
        keyBottom = key.bottom
        selRow = 0; selCol = 0
        startX = fingerX
        startY = fingerY ?: key.centerY()
        lastX = fingerX; lastY = startY
        moved = false
        selX.set(cellLeft(0, 0)); selY.set(cellTop(0))
        opShow.set(0f)
        opShow.animateTo(1f, dur(150), Tween.EASE_OUT)
        invalidate()
    }

    /** Display column of option [i] in a row. */
    private fun colOf(i: Int) = if (mirror) perRow - 1 - i else i
    private fun cellLeft(row: Int, i: Int) = opRect.left + pad + colOf(i) * cellW
    private fun cellTop(row: Int) = opRect.bottom - pad - (row + 1) * cellH

    private var startX = 0f
    private var startY = 0f
    private var moved = false

    private var lastX = 0f
    private var lastY = 0f

    fun moveSelection(x: Float, y: Float) {
        if (rows.isEmpty()) return
        lastX = x; lastY = y
        if (!moved) {
            if (kotlin.math.abs(x - startX) < cellW * 0.6f && kotlin.math.abs(y - startY) < cellH * 0.6f) return
            moved = true
        }
        var nr = -1; var nc = -1
        if (y <= keyBottom + cellH * 1.2f) {
            nr = floor((opRect.bottom - pad - y) / cellH).toInt().coerceIn(0, rows.size - 1)
            val col = floor((x - opRect.left - pad) / cellW).toInt().coerceIn(0, perRow - 1)
            nc = (if (mirror) perRow - 1 - col else col).coerceIn(0, rows[nr].size - 1)
        }
        if (nr != selRow || nc != selCol) {
            selRow = nr; selCol = nc
            if (nr >= 0) {
                selX.animateTo(cellLeft(nr, nc), dur(110))
                selY.animateTo(cellTop(nr), dur(110))
                onTick?.invoke()
            }
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
        val row = floor((opRect.bottom - pad - y) / cellH).toInt()
        if (row !in rows.indices) return null
        val col = floor((x - opRect.left - pad) / cellW).toInt().coerceIn(0, perRow - 1)
        val i = if (mirror) perRow - 1 - col else col
        return if (i in rows[row].indices) row to i else null
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
                    if (r >= 0) { selX.set(cellLeft(r, c)); selY.set(cellTop(r)); onTick?.invoke() }
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
            again = again || opShow.running || selX.running || selY.running
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
        if (selRow >= 0) {
            bg.color = theme.accent
            bg.alpha = (255 * show).toInt()
            val x = selX.value; val y = selY.value
            c.drawRoundRect(x + dp(1.5f), y + dp(1.5f), x + cellW - dp(1.5f), y + cellH - dp(1.5f), dp(prefs.keyRadiusDp), dp(prefs.keyRadiusDp), bg)
        }
        text.typeface = Ui.regular
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, o ->
                val x = cellLeft(r, i) + cellW / 2
                val y = cellTop(r) + cellH / 2
                text.color = if (r == selRow && i == selCol) theme.onAccent else theme.ink
                text.alpha = (255 * show).toInt()
                text.textSize = sp(if (glyphs(o.label) <= 2) 21f else Ui.BODY)
                val tw = text.measureText(o.label)
                if (tw > cellW - dp(6f)) text.textSize *= (cellW - dp(6f)) / tw
                val fm = text.fontMetrics
                c.drawText(o.label, x, y - (fm.ascent + fm.descent) / 2, text)
            }
        }
        c.restore()
    }
}
