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
import kotlin.math.min

/**
 * The strip above the keys: the toolbar when idle, pinyin and candidates while composing,
 * associations after a word, a paste chip after copying, the number keyboard's bar, or a
 * status line.
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
        fun onNumMode(mode: String) {}
        fun onDismissSuggestions() {}
        fun candidateOptions(index: Int): List<Output> = emptyList()
        fun onCandidateOption(index: Int, option: Output) {}
        /** The pinyin line was tapped at character [index] of the preedit. */
        fun onPreeditTap(index: Int) {}
        /** "press" when a candidate, chip or button is touched, "popup" when a menu opens. */
        fun onFeedback(kind: String) {}
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
    private var caret = -1          // caret position in the preedit, -1 when at the end
    private var candidates: List<String> = emptyList()
    private var notes: List<String> = emptyList()
    private var predictionLabel = ""
    private var mathTabs: List<Pair<String, String>> = emptyList()
    private var mathTab = ""
    private var mathFormat = "uni"
    private var simpleNum = false
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
    private var tabScroll = 0f
    private var tabContentW = 0f
    private var tabArea = RectF()
    private val scroller = OverScroller(context)
    private val hits = mutableListOf<Pair<RectF, () -> Unit>>()
    private val candRects = mutableListOf<RectF>()
    private var preeditRect = RectF()
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

    /** Updates the candidates. Only the first switch into this mode fades; later updates are instant. */
    fun showCandidates(pre: String, cands: List<String>, annotations: List<String> = emptyList(), caretIndex: Int = -1) {
        val entering = mode != Mode.CANDIDATES
        preedit = pre; candidates = cands; notes = annotations; caret = caretIndex
        if (entering) { fade.set(0f); fade.animateTo(1f, dur(120)) }
        scrollX0 = 0f; scroller.forceFinished(true)
        mode = Mode.CANDIDATES
        invalidate()
    }

    fun showPredictions(words: List<String>, label: String = "") {
        preedit = ""; candidates = words; notes = emptyList(); predictionLabel = label
        scrollX0 = 0f; scroller.forceFinished(true)
        switchTo(Mode.PREDICTIONS)
    }

    fun showClip(text: String, kind: String) { clipText = text; clipKind = kind; switchTo(Mode.CLIP) }

    /** The number keyboard's bar: the 标准/学术 switch first, then the calculator line or the math tabs. */
    fun showMath(tabs: List<Pair<String, String>>, current: String, format: String, simple: Boolean = false) {
        val keepResult = simple == simpleNum && mode == Mode.MATH
        mathTabs = tabs; mathTab = current; mathFormat = format; simpleNum = simple
        if (!keepResult) resultChip = null
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

    private fun pill(c: Canvas, r: RectF, color: Int) {
        bg.color = color
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
    }

    private fun drawToolbar(c: Canvas, a: Float) {
        val slot = width / tools.size.toFloat()
        tools.forEachIndexed { i, id ->
            val cx = slot * i + slot / 2
            val cy = height / 2f
            val active = id == activeTool
            if (active || pressedTool == id) {
                val w = dp(46f); val h = dp(32f)
                pill(c, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), if (active) theme.accentSoft else theme.soft)
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
        preeditRect = RectF()
        if (composing && preedit.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = sp(11.5f)
            tp.color = theme.hint
            tp.alpha = (255 * a).toInt()
            val x0 = dp(12f)
            c.drawText(preedit, x0, dp(13f), tp)
            preeditRect = RectF(0f, 0f, x0 + tp.measureText(preedit) + dp(12f), dp(17f))
            if (caret in 0 until preedit.length) {
                // the pinyin is being edited: show where letters will go
                val cx = x0 + tp.measureText(preedit, 0, caret)
                bg.color = theme.accent
                c.drawRect(cx - dp(0.8f), dp(3f), cx + dp(0.8f), dp(15f), bg)
            }
        } else if (!composing && predictionLabel.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(10.5f); tp.color = theme.accent; tp.alpha = (255 * a).toInt()
            c.drawText(predictionLabel, dp(12f), dp(12f), tp)
        }
        c.save()
        c.clipRect(0f, 0f, width - btnW, height.toFloat())
        candRects.clear()
        var x = dp(4f) - scrollX0
        val top = if (composing || predictionLabel.isNotEmpty()) dp(15f) else dp(4f)
        tp.textAlign = Paint.Align.LEFT
        val size = sp(18f * prefs.candidateScale)
        val noteSize = sp(10f)
        candidates.forEachIndexed { i, s ->
            tp.textSize = size
            val note = notes.getOrNull(i).orEmpty()
            val tw = tp.measureText(s)
            val nw = if (note.isNotEmpty()) run { tp.textSize = noteSize; tp.measureText(note) + dp(3f) } else 0f
            val cw = tw + nw + dp(24f)
            val rect = RectF(x, top, x + cw, height.toFloat() - dp(3f))
            candRects += RectF(rect).apply { offset(scrollX0, 0f) }
            if (rect.right > 0 && rect.left < width) {
                if (i == pressedCand) { bg.color = theme.soft; c.drawRoundRect(rect, dp(10f), dp(10f), bg) }
                tp.textSize = size
                tp.color = if (i == 0 && composing) theme.accent else theme.ink
                tp.alpha = (255 * a).toInt()
                text(c, s, x + dp(12f), rect.centerY())
                if (note.isNotEmpty()) {
                    // a small proofreading note or reading, raised like a superscript
                    tp.textSize = noteSize
                    tp.color = if (note.contains('→') || note.contains('⇄')) theme.accent else theme.hint
                    tp.alpha = (255 * a).toInt()
                    c.drawText(note, x + dp(12f) + tw + dp(3f), rect.centerY() - dp(3f), tp)
                }
            }
            x += cw
        }
        contentW = x + scrollX0
        c.restore()
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
        val label = clipText.replace('\n', ' ').trim()
        val tag = "粘贴" + if (clipKind.isNotEmpty()) " · $clipKind" else ""
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = sp(12f)
        val tagW = tp.measureText(tag)
        tp.textSize = sp(14f)
        val avail = width - 2 * closeW
        val maxText = avail - tagW - dp(40f)
        var shown = label
        if (tp.measureText(shown) > maxText) {
            var end = shown.length
            while (end > 1 && tp.measureText(shown, 0, end) + tp.measureText("…") > maxText) end--
            shown = shown.substring(0, end) + "…"
        }
        val w = tagW + tp.measureText(shown) + dp(40f)
        // centered in the bar, between equal margins
        val left = (width - w) / 2
        val r = RectF(left, dp(7f), left + w, height - dp(7f))
        pill(c, r, theme.accentSoft)
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
        // 标准 / 学术 switch, always in the same place
        val segW = dp(104f)
        val sr = RectF(dp(6f), dp(8f), dp(6f) + segW, height - dp(8f))
        pill(c, sr, theme.soft)
        listOf("simple" to "标准", "sci" to "学术").forEachIndexed { i, (id, name) ->
            val r = RectF(sr.left + i * segW / 2 + dp(2f), sr.top + dp(2f), sr.left + (i + 1) * segW / 2 - dp(2f), sr.bottom - dp(2f))
            val on = (id == "simple") == simpleNum
            if (on) pill(c, r, theme.accent)
            tp.color = if (on) theme.onAccent else theme.inkSoft
            tp.textSize = sp(12.5f); tp.isFakeBoldText = on
            text(c, name, r.centerX(), r.centerY())
            tp.isFakeBoldText = false
            hits += r to { listener?.onNumMode(id) }
        }
        val chip = resultChip
        var right = width - dp(6f)
        if (!simpleNum) {
            val fmtW = dp(92f)
            val fr = RectF(right - fmtW, dp(8f), right, height - dp(8f))
            pill(c, fr, theme.soft)
            listOf("uni" to "符号", "tex" to "LaTeX").forEachIndexed { i, (id, name) ->
                val r = RectF(fr.left + i * fmtW / 2 + dp(2f), fr.top + dp(2f), fr.left + (i + 1) * fmtW / 2 - dp(2f), fr.bottom - dp(2f))
                if (id == mathFormat) pill(c, r, theme.accentSoft)
                tp.color = if (id == mathFormat) theme.accent else theme.inkSoft
                tp.textSize = sp(12f)
                text(c, name, r.centerX(), r.centerY())
                hits += r to { listener?.onMathFormat(id) }
            }
            right = fr.left - dp(4f)
        }
        val midL = sr.right + dp(6f)
        if (chip != null) {
            tp.textSize = sp(16f)
            val label = "= $chip"
            val w = tp.measureText(label) + dp(28f)
            val r = RectF(midL, dp(7f), midL + w, height - dp(7f))
            pill(c, r, theme.accent)
            tp.color = theme.onAccent
            text(c, label, r.centerX(), r.centerY())
            tp.color = theme.hint; tp.textSize = sp(11.5f); tp.textAlign = Paint.Align.LEFT
            if (r.right + dp(70f) < right) text(c, "点一下上屏", r.right + dp(8f), height / 2f)
            hits += r to { listener?.onResultChip(chip) }
            return
        }
        if (simpleNum) {
            tp.textAlign = Paint.Align.LEFT
            tp.color = theme.hint; tp.textSize = sp(12f)
            text(c, "输入算式后点 = 出结果", midL + dp(4f), height / 2f)
            return
        }
        // scrollable math tabs between the switch and the format toggle
        tabArea = RectF(midL, 0f, right, height.toFloat())
        c.save()
        c.clipRect(tabArea)
        var x = midL - tabScroll
        tp.textSize = sp(13.5f)
        tp.typeface = Typeface.SERIF
        for ((id, name) in mathTabs) {
            val w = tp.measureText(name) + dp(13f)
            val r = RectF(x, dp(8f), x + w, height - dp(8f))
            val on = id == mathTab
            if (on) pill(c, r, theme.accentSoft)
            tp.color = if (on) theme.accent else theme.inkSoft; tp.alpha = (255 * a).toInt()
            tp.isFakeBoldText = on
            text(c, name, r.centerX(), r.centerY())
            tp.isFakeBoldText = false
            val hit = RectF(r).apply { left = max(left, tabArea.left); right = min(right, tabArea.right) }
            if (hit.width() > 0) hits += hit to { listener?.onMathTab(id) }
            x += w + dp(1f)
        }
        tabContentW = x + tabScroll - midL
        c.restore()
        tp.typeface = Typeface.DEFAULT
    }

    // ---- touch ----
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var draggingTabs = false
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
        overlay?.showOptions(RectF(r.left, r.bottom + r.height(), r.right, r.bottom + r.height() * 2), r.height(), opts, downX + ox, false)
        menuOpen = true
        listener?.onFeedback("popup")
    }

    private val scrollMax get() = max(0f, contentW - width + dp(48f))

    /** Rubber band: past either end the row follows the finger with growing resistance. */
    private fun rubber(over: Float): Float {
        val limit = dp(90f)
        return limit * (1f - 1f / (abs(over) / limit * 0.55f + 1f)) * if (over < 0) -1 else 1
    }

    private var rawScroll = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        val list = mode == Mode.CANDIDATES || mode == Mode.PREDICTIONS
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; lastX = e.x; dragging = false; draggingTabs = false; menuOpen = false
                rawScroll = scrollX0
                pressedCand = if (list && e.x < width - dp(44f) && !preeditRect.contains(e.x, e.y))
                    candRects.indexOfFirst { it.contains(e.x + scrollX0, e.y) } else -1
                if (pressedCand >= 0 && mode == Mode.CANDIDATES) handler.postDelayed(longRunnable, 450)
                if (mode == Mode.TOOLBAR) pressedTool = tools.getOrNull((e.x / (width / tools.size.toFloat())).toInt())
                if (pressedCand >= 0 || hits.any { it.first.contains(e.x, e.y) } || preeditRect.contains(e.x, e.y)) listener?.onFeedback("press")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuOpen) {
                    val (ox, oy) = overlayOffset()
                    overlay?.moveSelection(e.x + ox, e.y + oy)
                    return true
                }
                if (!dragging && abs(e.x - downX) > slop) {
                    if (list) { dragging = true; pressedCand = -1; handler.removeCallbacks(longRunnable) }
                    else if (mode == Mode.MATH && !simpleNum && tabArea.contains(downX, 0f)) draggingTabs = true
                }
                if (dragging) {
                    rawScroll -= e.x - lastX
                    scrollX0 = when {
                        rawScroll < 0 -> rubber(rawScroll)
                        rawScroll > scrollMax -> scrollMax + rubber(rawScroll - scrollMax)
                        else -> rawScroll
                    }
                    invalidate()
                }
                if (draggingTabs) {
                    tabScroll = (tabScroll - (e.x - lastX)).coerceIn(0f, max(0f, tabContentW - tabArea.width()))
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
                        if (scrollX0 < 0 || scrollX0 > scrollMax) {
                            // spring back from the stretched edge
                            scroller.springBack(scrollX0.toInt(), 0, 0, scrollMax.toInt(), 0, 0)
                        } else {
                            scroller.fling(scrollX0.toInt(), 0, -(vt?.xVelocity ?: 0f).toInt(), 0, 0, scrollMax.toInt(), 0, 0, dp(36f).toInt(), 0)
                        }
                        postInvalidateOnAnimation()
                    }
                    draggingTabs -> {}
                    preeditRect.contains(e.x, e.y) && mode == Mode.CANDIDATES && abs(e.x - downX) < slop -> {
                        tp.textSize = sp(11.5f)
                        val x = e.x - dp(12f)
                        var idx = 0
                        while (idx < preedit.length && tp.measureText(preedit, 0, idx + 1) - tp.measureText(preedit, idx, idx + 1) / 2 < x) idx++
                        listener?.onPreeditTap(idx)
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
                if (dragging) { scroller.springBack(scrollX0.toInt(), 0, 0, scrollMax.toInt(), 0, 0); postInvalidateOnAnimation() }
                pressedCand = -1; pressedTool = null; vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }
}
