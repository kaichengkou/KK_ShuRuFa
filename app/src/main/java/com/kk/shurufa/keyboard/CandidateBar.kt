package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max

/**
 * The strip above the keys: the toolbar when idle, pinyin and candidates while composing,
 * associations after a word, a paste chip after copying, the math tabs, or a status line.
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
        fun onClipChip(text: String) {}
        fun onDismissSuggestions() {}
        fun candidateOptions(index: Int): List<Output> = emptyList()
        fun onCandidateOption(index: Int, option: Output) {}
    }

    var listener: Listener? = null
    var overlay: PopupOverlay? = null
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    /** Toolbar button whose panel is open; drawn highlighted. */
    var activeTool: String? = null
        set(v) { field = v; invalidate() }

    enum class Mode { TOOLBAR, CANDIDATES, PREDICTIONS, CLIP, MATH, MESSAGE }
    var mode = Mode.TOOLBAR
        private set

    private var preedit = ""
    private var candidates: List<String> = emptyList()
    private var mathTabs: List<Pair<String, String>> = emptyList()
    private var mathTab = ""
    private var mathFormat = "uni"
    private var resultChip: String? = null
    private var clipText = ""
    private var clipKind = ""
    private var message = ""
    var expanded = false

    private val tools = listOf("ai", "emoji", "translate", "cursor", "clipboard", "quick")

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
    private val handler = Handler(Looper.getMainLooper())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())
    }

    private fun switchTo(m: Mode) {
        if (m != mode) { fade.set(0f); fade.animateTo(1f, dur(150)) }
        mode = m
        invalidate()
    }

    fun showToolbar() = switchTo(Mode.TOOLBAR)

    fun showCandidates(pre: String, cands: List<String>) {
        val fresh = mode != Mode.CANDIDATES || cands.firstOrNull() != candidates.firstOrNull()
        preedit = pre; candidates = cands
        if (fresh) { scrollX0 = 0f; scroller.forceFinished(true); fade.set(0.35f); fade.animateTo(1f, dur(120)) }
        mode = Mode.CANDIDATES
        invalidate()
    }

    fun showPredictions(words: List<String>) {
        preedit = ""; candidates = words
        scrollX0 = 0f; scroller.forceFinished(true)
        switchTo(Mode.PREDICTIONS)
    }

    fun showClip(text: String, kind: String) { clipText = text; clipKind = kind; switchTo(Mode.CLIP) }

    fun showMath(tabs: List<Pair<String, String>>, current: String, format: String) {
        mathTabs = tabs; mathTab = current; mathFormat = format; resultChip = null
        switchTo(Mode.MATH)
    }

    fun showResult(text: String?) { resultChip = text; invalidate() }

    fun showMessage(text: String) { message = text; switchTo(Mode.MESSAGE) }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val a = fade.value
        if (fade.running) postInvalidateOnAnimation()
        if (scroller.computeScrollOffset()) { scrollX0 = scroller.currX.toFloat(); postInvalidateOnAnimation() }
        tp.typeface = Typeface.DEFAULT
        when (mode) {
            Mode.TOOLBAR -> drawToolbar(c, a)
            Mode.CANDIDATES, Mode.PREDICTIONS -> drawCandidates(c, a)
            Mode.CLIP -> drawClip(c, a)
            Mode.MATH -> drawMath(c, a)
            Mode.MESSAGE -> {
                tp.color = theme.hint; tp.alpha = (255 * a).toInt(); tp.textSize = sp(13f); tp.textAlign = Paint.Align.CENTER
                text(c, message, width / 2f, height / 2f)
            }
        }
    }

    private fun text(c: Canvas, s: String, x: Float, cy: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, tp)
    }

    private fun drawToolbar(c: Canvas, a: Float) {
        val slot = width / tools.size.toFloat()
        tools.forEachIndexed { i, id ->
            val cx = slot * i + slot / 2
            val cy = height / 2f
            val active = id == activeTool
            if (active || pressedTool == id) {
                bg.color = if (active) theme.accentSoft else theme.soft
                val w = dp(46f); val h = dp(32f)
                c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, bg)
            }
            ip.color = if (active || id == "ai") theme.accent else theme.inkSoft
            ip.alpha = (255 * a).toInt()
            Icons.draw(c, id, cx, cy, dp(21f), ip, filled = id == "ai")
            hits += RectF(cx - slot / 2, 0f, cx + slot / 2, height.toFloat()) to { listener?.onTool(id) }
        }
    }

    private var pressedTool: String? = null

    private fun drawCandidates(c: Canvas, a: Float) {
        val btnW = dp(44f)
        val composing = mode == Mode.CANDIDATES
        if (composing && preedit.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = sp(11f)
            tp.color = theme.hint
            tp.alpha = (255 * a).toInt()
            c.drawText(preedit, dp(12f), dp(12.5f), tp)
        }
        c.save()
        c.clipRect(0f, 0f, width - btnW, height.toFloat())
        candRects.clear()
        var x = dp(4f) - scrollX0
        val top = if (composing) dp(15f) else dp(4f)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = sp(18f * prefs.candidateScale)
        candidates.forEachIndexed { i, s ->
            val cw = tp.measureText(s) + dp(24f)
            val rect = RectF(x, top, x + cw, height.toFloat() - dp(3f))
            candRects += RectF(rect).apply { offset(scrollX0, 0f) }
            if (rect.right > 0 && rect.left < width) {
                if (i == pressedCand) { bg.color = theme.soft; c.drawRoundRect(rect, dp(10f), dp(10f), bg) }
                tp.color = if (i == 0 && composing) theme.accent else theme.ink
                tp.alpha = (255 * a).toInt()
                text(c, s, x + dp(12f), rect.centerY())
            }
            x += cw
        }
        contentW = x + scrollX0
        c.restore()
        // fade the scrolled-out edge so the row does not look cut off
        val ex = width - btnW / 2
        ip.color = theme.inkSoft
        if (composing) Icons.draw(c, if (expanded) "expand" else "collapse", ex, height / 2f, dp(20f), ip)
        else Icons.draw(c, "close", ex, height / 2f, dp(18f), ip)
        bg.color = theme.divider
        c.drawRect(width - btnW, dp(13f), width - btnW + dp(1f), height - dp(13f), bg)
        hits += RectF(width - btnW, 0f, width.toFloat(), height.toFloat()) to {
            if (composing) listener?.onExpand() else listener?.onDismissSuggestions()
        }
    }

    private fun drawClip(c: Canvas, a: Float) {
        val closeW = dp(44f)
        tp.textSize = sp(13.5f)
        tp.textAlign = Paint.Align.LEFT
        val label = clipText.replace('\n', ' ').trim()
        val tag = "粘贴" + if (clipKind.isNotEmpty()) " · $clipKind" else ""
        val tagW = run { tp.textSize = sp(12f); tp.measureText(tag) }
        tp.textSize = sp(14f)
        val maxText = width - closeW - dp(16f) - tagW - dp(40f)
        var shown = label
        if (tp.measureText(shown) > maxText) {
            var end = shown.length
            while (end > 1 && tp.measureText(shown, 0, end) + tp.measureText("…") > maxText) end--
            shown = shown.substring(0, end) + "…"
        }
        val w = tagW + tp.measureText(shown) + dp(40f)
        val r = RectF(dp(8f), dp(7f), dp(8f) + w, height - dp(7f))
        bg.color = theme.accentSoft
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
        tp.color = theme.accent; tp.alpha = (255 * a).toInt(); tp.textSize = sp(12f); tp.isFakeBoldText = true
        text(c, tag, r.left + dp(14f), r.centerY())
        tp.isFakeBoldText = false
        tp.color = theme.ink; tp.alpha = (255 * a).toInt(); tp.textSize = sp(14f)
        text(c, shown, r.left + dp(24f) + tagW, r.centerY())
        hits += r to { listener?.onClipChip(clipText) }
        ip.color = theme.inkSoft
        Icons.draw(c, "close", width - closeW / 2, height / 2f, dp(18f), ip)
        hits += RectF(width - closeW, 0f, width.toFloat(), height.toFloat()) to { listener?.onDismissSuggestions() }
    }

    private fun drawMath(c: Canvas, a: Float) {
        tp.textAlign = Paint.Align.CENTER
        val chip = resultChip
        val fmtW = dp(98f)
        val fx = width - fmtW - dp(6f)
        val fr = RectF(fx, dp(8f), fx + fmtW, height - dp(8f))
        bg.color = theme.soft; c.drawRoundRect(fr, fr.height() / 2, fr.height() / 2, bg)
        val half = fmtW / 2
        listOf("uni" to "符号", "tex" to "LaTeX").forEachIndexed { i, (id, name) ->
            val r = RectF(fx + i * half + dp(2f), fr.top + dp(2f), fx + (i + 1) * half - dp(2f), fr.bottom - dp(2f))
            if (id == mathFormat) { bg.color = theme.accent; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg) }
            tp.color = if (id == mathFormat) theme.onAccent else theme.inkSoft
            tp.textSize = sp(12.5f)
            text(c, name, r.centerX(), r.centerY())
            hits += r to { listener?.onMathFormat(id) }
        }
        if (chip != null) {
            tp.textSize = sp(16f)
            val label = "= $chip"
            val w = tp.measureText(label) + dp(28f)
            val r = RectF(dp(8f), dp(7f), dp(8f) + w, height - dp(7f))
            bg.color = theme.accent; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
            tp.color = theme.onAccent
            text(c, label, r.centerX(), r.centerY())
            tp.color = theme.hint; tp.textSize = sp(12f); tp.textAlign = Paint.Align.LEFT
            text(c, "点一下上屏", r.right + dp(8f), height / 2f)
            hits += r to { listener?.onResultChip(chip) }
            return
        }
        var x = dp(6f)
        tp.textSize = sp(13.5f)
        tp.typeface = Typeface.SERIF
        for ((id, name) in mathTabs) {
            val w = tp.measureText(name) + dp(13f)
            if (x + w > fx - dp(4f)) break
            val r = RectF(x, dp(8f), x + w, height - dp(8f))
            val on = id == mathTab
            if (on) { bg.color = theme.accentSoft; c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg) }
            tp.color = if (on) theme.accent else theme.inkSoft; tp.alpha = (255 * a).toInt()
            tp.isFakeBoldText = on
            text(c, name, r.centerX(), r.centerY())
            tp.isFakeBoldText = false
            hits += r to { listener?.onMathTab(id) }
            x += w + dp(1f)
        }
        tp.typeface = Typeface.DEFAULT
    }

    // ---- touch ----
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressedCand = -1
    private var menuOpen = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun overlayOffset(): Pair<Float, Float> {
        val o = overlay ?: return 0f to 0f
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); o.getLocationInWindow(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    private val longRunnable = Runnable {
        val i = pressedCand
        if (i < 0 || dragging) return@Runnable
        val opts = listener?.candidateOptions(i).orEmpty()
        if (opts.isEmpty()) return@Runnable
        val r = RectF(candRects[i]).apply { offset(-scrollX0, 0f) }
        val (ox, oy) = overlayOffset()
        r.offset(ox, oy)
        // the popup opens below the bar would cover the keys; open it over the bar instead
        overlay?.showOptions(RectF(r.left, r.bottom + r.height(), r.right, r.bottom + r.height() * 2), r.height(), opts, downX + ox, false)
        menuOpen = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        val scrollMax = max(0f, contentW - width + dp(48f))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; lastX = e.x; dragging = false; menuOpen = false
                val inList = (mode == Mode.CANDIDATES || mode == Mode.PREDICTIONS) && e.x < width - dp(44f)
                pressedCand = if (inList) candRects.indexOfFirst { it.contains(e.x + scrollX0, e.y) } else -1
                if (pressedCand >= 0 && mode == Mode.CANDIDATES) handler.postDelayed(longRunnable, 450)
                if (mode == Mode.TOOLBAR) pressedTool = tools.getOrNull((e.x / (width / tools.size.toFloat())).toInt())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuOpen) {
                    val (ox, oy) = overlayOffset()
                    overlay?.moveSelection(e.x + ox, e.y + oy)
                    return true
                }
                if ((mode == Mode.CANDIDATES || mode == Mode.PREDICTIONS) && !dragging && abs(e.x - downX) > slop) {
                    dragging = true; pressedCand = -1; handler.removeCallbacks(longRunnable)
                }
                if (dragging) {
                    scrollX0 = (scrollX0 - (e.x - lastX)).coerceIn(0f, scrollMax)
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                when {
                    menuOpen -> {
                        overlay?.selected()?.let { listener?.onCandidateOption(pressedCand, it) }
                        overlay?.hideOptions()
                        menuOpen = false
                    }
                    dragging -> {
                        vt?.computeCurrentVelocity(1000)
                        scroller.fling(scrollX0.toInt(), 0, -(vt?.xVelocity ?: 0f).toInt(), 0, 0, scrollMax.toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                    pressedCand >= 0 -> {
                        val i = pressedCand
                        if (mode == Mode.PREDICTIONS) listener?.onCandidate(-1 - i) else listener?.onCandidate(i)
                    }
                    else -> hits.firstOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
                }
                pressedCand = -1; pressedTool = null
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (menuOpen) { overlay?.hideOptions(); menuOpen = false }
                pressedCand = -1; pressedTool = null; vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }
}
