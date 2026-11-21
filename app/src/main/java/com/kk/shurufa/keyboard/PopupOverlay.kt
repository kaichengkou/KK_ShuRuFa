package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Transparent layer above the whole input view. Draws the key preview bubble and the
 * long-press option popup, both animated.
 */
@SuppressLint("ViewConstructor")
class PopupOverlay(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** Called when the selection moves to another option (for haptics). */
    var onTick: (() -> Unit)? = null

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
    private val pvRect = RectF()
    private var pvLabel = ""
    private var pvOld: String? = null
    private var pvDir: Dir? = null
    private val pvAlpha = Tween(0f)
    /** The bubble grows out of the key with a little spring in it. */
    private val pvScale = Spring(0f, stiffness = 1500f, dampingRatio = 0.6f)
    private val pvSwap = Tween(1f)

    fun showPreview(r: RectF, label: String, dir: Dir?) {
        val wasVisible = pvAlpha.target > 0.5f
        if (wasVisible && label != pvLabel) {
            pvOld = pvLabel
            pvSwap.set(0f)
            pvSwap.animateTo(1f, dur(110), Motion.MOVE)
        } else if (!wasVisible) {
            pvOld = null
            pvSwap.set(1f)
            pvAlpha.set(0f)
            pvScale.snap(0.82f)
        }
        pvRect.set(r)
        pvLabel = label
        pvDir = dir
        pvAlpha.animateTo(1f, dur(70), Motion.ENTER)
        pvScale.enabled = Motion.on(prefs)
        pvScale.animateTo(1f)
        invalidate()
    }

    fun hidePreview() {
        if (pvAlpha.target == 0f) return
        pvAlpha.animateTo(0f, dur(Motion.POP_OUT), Motion.EXIT)
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
    // the highlight glides between options and keeps its momentum when the finger changes its mind
    private val selX = Spring(0f, stiffness = 1100f, dampingRatio = 0.88f)
    private val selY = Spring(0f, stiffness = 1100f, dampingRatio = 0.88f)
    private val selW = Spring(0f, stiffness = 1100f, dampingRatio = 0.88f)
    private val pad get() = dp(5f)

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
            val padX = dp(16f)
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
            if (floor(avail / cw).toInt() < 1) cw = avail
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
        cells[0][0].let { selX.snap(it.left); selY.snap(it.top); selW.snap(it.width()) }
        for (s in arrayOf(selX, selY, selW)) s.enabled = Motion.on(prefs)
        opShow.set(0f)
        opShow.animateTo(1f, dur(Motion.POP_IN), Motion.ENTER)
        invalidate()
    }

    fun showOptions(key: RectF, keyH: Float, options: List<Output>, fingerX: Float, fingerY: Float? = null) =
        showOptionRows(key, keyH, listOf(options), fingerX, fingerY)

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
        if (animate) { selX.animateTo(rect.left); selY.animateTo(rect.top); selW.animateTo(rect.width()) }
        else { selX.snap(rect.left); selY.snap(rect.top); selW.snap(rect.width()) }
    }

    fun moveSelection(x: Float, y: Float) {
        if (rows.isEmpty()) return
        lastX = x; lastY = y
        if (!moved) {
            if (abs(x - startX) < minCellW * 0.6f && abs(y - startY) < cellH * 0.6f) return
            moved = true
        }
        var nr = -1; var nc = -1
        if (y <= keyBottom + cellH * 1.2f) {
            nr = floor((opRect.bottom - pad - y) / cellH).toInt().coerceIn(0, rows.size - 1)
            nc = colAt(nr, x)
        }
        if (nr != selRow || nc != selCol) {
            val wasNone = selRow < 0
            selRow = nr; selCol = nc
            if (nr >= 0) { moveSel(nr, nc, animate = !wasNone); onTick?.invoke() }
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
    fun pickedByDrag(): Boolean {
        val m = dp(10f)
        return moved && selRow >= 0 && lastX >= opRect.left - m && lastX <= opRect.right + m && lastY >= opRect.top - m && lastY <= opRect.bottom + m
    }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!sticky) return false
        val hit = optionAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val r = hit?.first ?: -1
                val c = hit?.second ?: -1
                if (r != selRow || c != selCol) {
                    selRow = r; selCol = c
                    if (r >= 0) { moveSel(r, c, false); onTick?.invoke() }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> closeSticky(hit?.let { (r, c) -> rows[r][c] })
            MotionEvent.ACTION_CANCEL -> closeSticky(null)
        }
        return true
    }

    fun hideOptions() {
        opShow.animateTo(0f, dur(Motion.POP_OUT), Motion.EXIT)
        invalidate()
    }

    // ---- notices: a short line floating just above the keyboard, over the app, never in the way
    // of the keys or the candidates; it goes away by itself ----
    private var toastText = ""
    private var toastBottom = 0f
    private val toastAnim = Tween(0f)
    private val toastRect = RectF()
    private val hideToast = Runnable { toastAnim.animateTo(0f, dur(180), Motion.EXIT); invalidate() }

    /** Shows [text] above the keyboard, whose top edge is at [keyboardTop] in this layer. */
    fun toast(text: String, keyboardTop: Float) {
        toastText = text
        toastBottom = keyboardTop - dp(10f)
        removeCallbacks(hideToast)
        toastAnim.animateTo(1f, dur(200), Motion.ENTER)
        postDelayed(hideToast, 1600L + 40L * text.length.coerceAtMost(30))
        invalidate()
    }

    private fun drawToast(c: Canvas, a: Float) {
        text.typeface = Ui.regular; text.textSize = sp(Ui.BODY)
        val w = minOf(text.measureText(toastText) + dp(36f), width - dp(24f))
        val h = dp(36f)
        val rise = (1 - a) * dp(8f)
        toastRect.set((width - w) / 2, toastBottom - h + rise, (width + w) / 2, toastBottom + rise)
        bg.color = theme.popup
        bg.alpha = (255 * a).toInt()
        bg.setShadowLayer(dp(10f), 0f, dp(3f), Colors.alpha(Color.BLACK, ((if (theme.dark) 0x70 else 0x38) * a).toInt()))
        c.drawRoundRect(toastRect, h / 2, h / 2, bg)
        bg.clearShadowLayer()
        text.color = theme.ink; text.alpha = (255 * a).toInt()
        drawText(c, toastText, toastRect.centerX(), toastRect.centerY(), text)
    }

    // ---- drawing ----
    override fun onDraw(c: Canvas) {
        var again = false
        val ta = toastAnim.value
        if (ta > 0.01f && toastText.isNotEmpty()) { drawToast(c, ta); again = toastAnim.running }
        val pa = pvAlpha.value
        if (pa > 0.01f && opShow.value < 0.5f) {
            if (pvScale.running) pvScale.step()
            again = pvAlpha.running || pvSwap.running || pvScale.running
            drawPreview(c, pa, pvScale.value)
        }
        val os = opShow.value
        if (os > 0.01f && rows.isNotEmpty()) {
            for (s in arrayOf(selX, selY, selW)) if (s.running) { s.step(); again = true }
            again = again || opShow.running
            drawOptions(c, os)
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun bubble(c: Canvas, r: RectF, alpha: Float) {
        bg.color = theme.popup
        bg.alpha = (255 * alpha).toInt()
        bg.setShadowLayer(dp(8f), 0f, dp(2.5f), Colors.alpha(Color.BLACK, ((if (theme.dark) 0x66 else 0x30) * alpha).toInt()))
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        c.drawRoundRect(r, rad, rad, bg)
        bg.clearShadowLayer()
    }

    private fun drawPreview(c: Canvas, alpha: Float, scale: Float) {
        c.save()
        c.scale(scale, scale, pvRect.centerX(), pvRect.bottom)
        val a = alpha.coerceIn(0f, 1f)
        bubble(c, pvRect, a)
        val p = pvSwap.value
        val slide = dp(10f)
        c.save(); c.clipRect(pvRect)
        pvOld?.let { old ->
            if (p < 1f) drawLabel(c, old, pvRect.centerX() - dirX(pvDir) * slide * p, pvRect.centerY() - dirY(pvDir) * slide * p, (1 - p) * a, false)
        }
        drawLabel(c, pvLabel, pvRect.centerX() + dirX(pvDir) * slide * (1 - p), pvRect.centerY() + dirY(pvDir) * slide * (1 - p), p * a, pvDir != null)
        c.restore()
        c.restore()
    }

    private fun dirX(d: Dir?) = when (d) { Dir.LEFT -> -1f; Dir.RIGHT -> 1f; else -> 0f }
    private fun dirY(d: Dir?) = when (d) { Dir.UP -> -1f; Dir.DOWN -> 1f; else -> 0f }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float, alpha: Float, accent: Boolean) {
        text.typeface = Ui.regular
        text.color = if (accent) theme.accent else theme.ink
        text.alpha = (255 * alpha).toInt()
        text.textSize = sp(if (s.codePointCount(0, s.length) <= 2) Ui.PREVIEW else 17f)
        val maxW = pvRect.width() - dp(8f)
        val tw = text.measureText(s)
        if (tw > maxW) text.textSize *= maxW / tw
        drawText(c, s, x, y, text)
    }

    private fun drawOptions(c: Canvas, show: Float) {
        c.save()
        val s = 0.92f + 0.08f * show
        c.scale(s, s, opRect.centerX(), opRect.bottom)
        bubble(c, opRect, show)
        val alpha = (255 * show).toInt()
        val inset = dp(1.5f)
        val rad = if (words) (cellH - 2 * inset) / 2 else dp(prefs.keyRadiusDp)
        // hairlines between word pills, except beside the highlighted one
        if (words) {
            bg.color = Colors.fade(theme.divider, 0.55f * show)
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
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, o ->
                val rect = cells[r][i]
                val on = r == selRow && i == selCol
                // 取消 is the way out, not a choice: quieter
                text.color = when { on -> theme.onAccent; o.text == "§cancel" -> theme.inkSoft; else -> theme.ink }
                text.alpha = alpha
                drawText(c, o.label, rect.centerX(), rect.centerY(), text)
            }
        }
        c.restore()
    }
}
