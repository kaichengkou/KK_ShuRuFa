package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max

/**
 * The strip above the keys. Shows the toolbar when idle, the pinyin and candidates while
 * composing, the math tabs on the math keyboard, or a short status message.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val prefs: Prefs) : View(context) {

    interface Listener {
        fun onTool(id: String)
        fun onCandidate(index: Int)
        fun onExpand()
        fun onMathTab(id: String)
        fun onMathFormat(format: String)
        fun onResultChip(text: String)
    }

    var listener: Listener? = null
    var theme: KbTheme = KbTheme.resolve(context, prefs)

    enum class Mode { TOOLBAR, CANDIDATES, MATH, MESSAGE }
    var mode = Mode.TOOLBAR
        private set

    private var preedit = ""
    private var candidates: List<String> = emptyList()
    private var comments: List<String> = emptyList()
    private var mathTabs: List<Pair<String, String>> = emptyList()
    private var mathTab = ""
    private var mathFormat = "uni"
    private var resultChip: String? = null
    private var message = ""
    var expanded = false

    private val tools = listOf("ai", "emoji", "translate", "cursor", "clipboard", "settings")

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dur(ms: Long) = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)

    private val fade = Tween(1f)
    private var scrollX0 = 0f
    private val scroller = OverScroller(context)
    private val hits = mutableListOf<Pair<RectF, () -> Unit>>()
    private val candRects = mutableListOf<RectF>()
    private var contentW = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(48f).toInt())
    }

    private fun switchTo(m: Mode) {
        if (m != mode) { fade.set(0f); fade.animateTo(1f, dur(140)) }
        mode = m
        invalidate()
    }

    fun showToolbar() { switchTo(Mode.TOOLBAR) }

    fun showCandidates(pre: String, cands: List<String>, comms: List<String>) {
        val fresh = mode != Mode.CANDIDATES || cands.firstOrNull() != candidates.firstOrNull()
        preedit = pre; candidates = cands; comments = comms
        if (fresh) { scrollX0 = 0f; scroller.forceFinished(true); fade.set(0.3f); fade.animateTo(1f, dur(110)) }
        mode = Mode.CANDIDATES
        invalidate()
    }

    fun showMath(tabs: List<Pair<String, String>>, current: String, format: String) {
        mathTabs = tabs; mathTab = current; mathFormat = format; resultChip = null
        switchTo(Mode.MATH)
    }

    fun showResult(text: String?) { resultChip = text; invalidate() }

    fun showMessage(text: String) { message = text; switchTo(Mode.MESSAGE) }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.bar)
        hits.clear()
        val a = fade.value
        if (fade.running) postInvalidateOnAnimation()
        if (scroller.computeScrollOffset()) { scrollX0 = scroller.currX.toFloat(); postInvalidateOnAnimation() }
        when (mode) {
            Mode.TOOLBAR -> drawToolbar(c, a)
            Mode.CANDIDATES -> drawCandidates(c, a)
            Mode.MATH -> drawMath(c, a)
            Mode.MESSAGE -> {
                tp.color = theme.hint; tp.alpha = (255 * a).toInt(); tp.textSize = sp(13.5f); tp.textAlign = Paint.Align.CENTER
                val fm = tp.fontMetrics
                c.drawText(message, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, tp)
            }
        }
    }

    private fun drawToolbar(c: Canvas, a: Float) {
        val n = tools.size
        val slot = width / n.toFloat()
        val r = dp(19f)
        tools.forEachIndexed { i, id ->
            val cx = slot * i + slot / 2
            val cy = height / 2f
            if (id != "settings") {
                bg.color = if (pressedTool == id) theme.keyPressed else theme.tool
                bg.alpha = (255 * a).toInt()
                c.drawCircle(cx, cy, r, bg)
            }
            ip.color = theme.ink; ip.alpha = (255 * a).toInt()
            Icons.draw(c, id, cx, cy, dp(21f), ip, filled = id == "ai")
            hits += RectF(cx - slot / 2, 0f, cx + slot / 2, height.toFloat()) to { listener?.onTool(id) }
        }
    }

    private var pressedTool: String? = null

    private fun drawCandidates(c: Canvas, a: Float) {
        val expandW = dp(44f)
        // pinyin line
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = sp(11.5f)
        tp.color = theme.hint
        tp.alpha = (255 * a).toInt()
        c.drawText(preedit, dp(12f), dp(13f), tp)
        // candidates
        c.save()
        c.clipRect(0f, 0f, width - expandW, height.toFloat())
        candRects.clear()
        var x = dp(4f) - scrollX0
        val top = dp(16f)
        tp.textSize = sp(18.5f * prefs.candidateScale)
        val ctp = Paint(tp).apply { textSize = sp(11f); color = theme.hint }
        candidates.forEachIndexed { i, s ->
            val cw = tp.measureText(s) + (comments.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { ctp.measureText(it) + dp(3f) } ?: 0f) + dp(22f)
            val rect = RectF(x, top - dp(2f), x + cw, height.toFloat() - dp(2f))
            candRects += RectF(rect).apply { offset(scrollX0, 0f) }
            if (rect.right > 0 && rect.left < width) {
                if (i == pressedCand) { bg.color = theme.keyPressed; c.drawRoundRect(rect, dp(8f), dp(8f), bg) }
                tp.color = if (i == 0) theme.accent else theme.ink
                tp.alpha = (255 * a).toInt()
                tp.isFakeBoldText = i == 0
                val fm = tp.fontMetrics
                val baseY = rect.centerY() - (fm.ascent + fm.descent) / 2
                c.drawText(s, x + dp(11f), baseY, tp)
                comments.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                    c.drawText(it, x + dp(11f) + tp.measureText(s) + dp(3f), baseY, ctp)
                }
                tp.isFakeBoldText = false
            }
            x += cw
        }
        contentW = x + scrollX0
        c.restore()
        // expand button
        val ex = width - expandW / 2
        ip.color = theme.ink
        Icons.draw(c, if (expanded) "expand" else "collapse", ex, height / 2f, dp(20f), ip)
        bg.color = theme.border
        c.drawRect(width - expandW, dp(12f), width - expandW + dp(1f), height - dp(12f), bg)
        hits += RectF(width - expandW, 0f, width.toFloat(), height.toFloat()) to { listener?.onExpand() }
    }

    private fun drawMath(c: Canvas, a: Float) {
        tp.textAlign = Paint.Align.CENTER
        val chip = resultChip
        // format toggle on the right
        val fmtW = dp(98f)
        val fx = width - fmtW - dp(6f)
        val fr = RectF(fx, dp(9f), fx + fmtW, height - dp(9f))
        bg.color = theme.tool; c.drawRoundRect(fr, fr.height() / 2, fr.height() / 2, bg)
        val half = fmtW / 2
        listOf("uni" to "符号", "tex" to "LaTeX").forEachIndexed { i, (id, name) ->
            val r = RectF(fx + i * half + dp(2f), fr.top + dp(2f), fx + (i + 1) * half - dp(2f), fr.bottom - dp(2f))
            if (id == mathFormat) { bg.color = theme.accent; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg) }
            tp.color = if (id == mathFormat) theme.onAccent else theme.ink
            tp.textSize = sp(12.5f)
            val fm = tp.fontMetrics
            c.drawText(name, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
            hits += r to { listener?.onMathFormat(id) }
        }
        if (chip != null) {
            tp.textSize = sp(16f)
            val label = "= $chip"
            val w = tp.measureText(label) + dp(28f)
            val r = RectF(dp(8f), dp(8f), dp(8f) + w, height - dp(8f))
            bg.color = theme.accent; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
            tp.color = theme.onAccent
            val fm = tp.fontMetrics
            c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
            tp.color = theme.hint; tp.textSize = sp(12f); tp.textAlign = Paint.Align.LEFT
            c.drawText("点一下上屏", r.right + dp(8f), height / 2f + dp(4f), tp)
            hits += r to { listener?.onResultChip(chip) }
            return
        }
        // tabs
        var x = dp(6f)
        tp.textSize = sp(13.5f)
        tp.typeface = android.graphics.Typeface.SERIF
        for ((id, name) in mathTabs) {
            val w = tp.measureText(name) + dp(13f)
            if (x + w > fx - dp(4f)) break
            val r = RectF(x, dp(8f), x + w, height - dp(8f))
            if (id == mathTab) { bg.color = theme.key; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg) }
            tp.color = theme.ink; tp.alpha = (255 * a).toInt()
            tp.isFakeBoldText = id == mathTab
            val fm = tp.fontMetrics
            c.drawText(name, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, tp)
            tp.isFakeBoldText = false
            hits += r to { listener?.onMathTab(id) }
            x += w + dp(1f)
        }
        tp.typeface = android.graphics.Typeface.DEFAULT
    }

    // ---- touch ----
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressedCand = -1
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; lastX = e.x; dragging = false
                if (mode == Mode.CANDIDATES && e.x < width - dp(44f)) pressedCand = candRects.indexOfFirst { it.contains(e.x + scrollX0, e.y) }
                if (mode == Mode.TOOLBAR) pressedTool = tools.getOrNull((e.x / (width / tools.size.toFloat())).toInt())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.CANDIDATES && !dragging && abs(e.x - downX) > slop) { dragging = true; pressedCand = -1 }
                if (dragging) {
                    scrollX0 = (scrollX0 - (e.x - lastX)).coerceIn(0f, max(0f, contentW - width + dp(48f)))
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    scroller.fling(scrollX0.toInt(), 0, -(vt?.xVelocity ?: 0f).toInt(), 0, 0, max(0f, contentW - width + dp(48f)).toInt(), 0, 0)
                    postInvalidateOnAnimation()
                } else if (mode == Mode.CANDIDATES && pressedCand >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onCandidate(pressedCand)
                } else {
                    hits.firstOrNull { it.first.contains(e.x, e.y) }?.let {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        it.second()
                    }
                }
                pressedCand = -1; pressedTool = null
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedCand = -1; pressedTool = null; vt?.recycle(); vt = null; invalidate() }
        }
        return true
    }
}
