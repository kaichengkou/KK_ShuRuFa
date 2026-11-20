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

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dur(ms: Long) = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
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
    private var opts: List<Output> = emptyList()
    private var opRect = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private var perRow = 1
    private var nRows = 1
    private var sel = 0
    private var keyBottom = 0f
    private var opMath = false
    private val opShow = Tween(0f)
    private val selX = Tween(0f)
    private val selY = Tween(0f)

    fun showOptions(key: RectF, keyH: Float, options: List<Output>, fingerX: Float, math: Boolean) {
        opts = options
        opMath = math
        val pad = dp(5f)
        cellW = max(key.width() * 0.92f, dp(40f))
        cellH = keyH * 1.02f
        perRow = min(options.size, min(8, floor((width - 2 * pad - dp(8f)) / cellW).toInt().coerceAtLeast(1)))
        nRows = ceil(options.size / perRow.toFloat()).toInt()
        val w = perRow * cellW + 2 * pad
        val h = nRows * cellH + 2 * pad
        var left = key.centerX() - cellW / 2 - pad
        left = left.coerceIn(dp(4f), width - w - dp(4f))
        val top = max(dp(2f), key.top - h - dp(6f))
        opRect = RectF(left, top, left + w, top + h)
        keyBottom = key.bottom
        sel = colAt(fingerX)
        selX.set(cellLeft(sel)); selY.set(cellTop(sel))
        opShow.set(0f)
        opShow.animateTo(1f, dur(150), Tween.EASE_OUT)
        invalidate()
    }

    private fun colAt(x: Float) = floor((x - opRect.left - dp(5f)) / cellW).toInt().coerceIn(0, min(perRow, opts.size) - 1)
    private fun cellLeft(i: Int) = opRect.left + dp(5f) + (i % perRow) * cellW
    private fun cellTop(i: Int) = opRect.bottom - dp(5f) - (i / perRow + 1) * cellH

    fun moveSelection(x: Float, y: Float) {
        if (opts.isEmpty()) return
        val newSel = if (y > keyBottom + cellH * 1.2f) -1 else {
            val row = floor((opRect.bottom - dp(5f) - y) / cellH).toInt().coerceIn(0, nRows - 1)
            (row * perRow + colAt(x)).coerceAtMost(opts.size - 1)
        }
        if (newSel != sel) {
            sel = newSel
            if (sel >= 0) {
                selX.animateTo(cellLeft(sel), dur(110))
                selY.animateTo(cellTop(sel), dur(110))
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            invalidate()
        }
    }

    fun selected(): Output? = opts.getOrNull(sel)

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
        if (os > 0.01f && opts.isNotEmpty()) {
            again = again || opShow.running || selX.running || selY.running
            drawOptions(c, os)
        }
        if (again) postInvalidateOnAnimation()
    }

    private fun bubble(c: Canvas, r: RectF, alpha: Float) {
        bg.color = theme.popup
        bg.alpha = (255 * alpha).toInt()
        bg.setShadowLayer(dp(6f), 0f, dp(2f), (0x40 * alpha).toInt() shl 24)
        c.drawRoundRect(r, dp(10f), dp(10f), bg)
        bg.clearShadowLayer()
    }

    private fun drawPreview(c: Canvas, show: Float) {
        c.save()
        val s = 0.85f + 0.15f * show
        c.scale(s, s, pvRect.centerX(), pvRect.bottom)
        val a = show.coerceIn(0f, 1f)
        bubble(c, pvRect, a)
        text.typeface = if (pvMath) Typeface.SERIF else Typeface.DEFAULT
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
        if (sel >= 0) {
            bg.color = theme.accent
            bg.alpha = (255 * show).toInt()
            val x = selX.value; val y = selY.value
            c.drawRoundRect(x + dp(1f), y + dp(1f), x + cellW - dp(1f), y + cellH - dp(1f), dp(8f), dp(8f), bg)
        }
        text.typeface = if (opMath) Typeface.SERIF else Typeface.DEFAULT
        opts.forEachIndexed { i, o ->
            val x = cellLeft(i) + cellW / 2
            val y = cellTop(i) + cellH / 2
            text.color = if (i == sel) theme.onAccent else theme.ink
            text.alpha = (255 * show).toInt()
            text.textSize = sp(if (o.label.codePointCount(0, o.label.length) <= 2) 21f else 14f)
            val tw = text.measureText(o.label)
            if (tw > cellW - dp(6f)) text.textSize *= (cellW - dp(6f)) / tw
            val fm = text.fontMetrics
            c.drawText(o.label, x, y - (fm.ascent + fm.descent) / 2, text)
        }
        c.restore()
    }
}
