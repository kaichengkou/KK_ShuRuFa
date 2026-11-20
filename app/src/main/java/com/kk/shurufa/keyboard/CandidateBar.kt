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
        /** In pinyin editing, the caret was moved to [pos] (a position in the typed letters). */
        fun onEditCaret(pos: Int) {}
        fun onEditDone() {}
        /** "press" when a candidate, chip or button is touched, "popup" when a menu opens. */
        fun onFeedback(kind: String) {}
    }

    var listener: Listener? = null
    var overlay: PopupOverlay? = null
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    /** Toolbar button whose panel is open; drawn highlighted. */
    var activeTool: String? = null
        set(v) { field = v; invalidate() }

    enum class Mode { TOOLBAR, CANDIDATES, PREDICTIONS, CLIP, MATH, MESSAGE, EDIT }

    /** When set, vertical drags on the toolbar resize the open panel (phase 0 start, 1 move, 2 end). */
    var onVerticalDrag: ((phase: Int, rawY: Float, velocityY: Float) -> Unit)? = null

    private var editInput = ""
    private var editCaret = 0
    private var editGaps: Set<Int> = emptySet()
    private val editPos = mutableListOf<Float>()   // x of each caret position 0..len
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

    /** The user's toolbar (settings → 工具栏); 快捷面板 is always last. */
    private val tools get() = prefs.toolbar + "quick"

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
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(50f).toInt())
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
        // the switches slide when they change while the bar stays on screen, and jump otherwise
        val seg = if (simple) 0f else 1f
        val fmt = if (format == "tex") 1f else 0f
        if (mode == Mode.MATH) {
            if (seg != segPos.target) segPos.animateTo(seg, dur(220))
            if (fmt != fmtPos.target) fmtPos.animateTo(fmt, dur(220))
        } else { segPos.set(seg); fmtPos.set(fmt) }
        mathTabs = tabs; mathTab = current; mathFormat = format; simpleNum = simple
        if (!keepResult) resultChip = null
        switchTo(Mode.MATH)
    }

    fun showResult(text: String?) { resultChip = text; invalidate() }

    fun showMessage(text: String) { message = text; switchTo(Mode.MESSAGE) }

    /** 拼音编辑: the typed letters, large, with a caret that can be dragged letter by letter. */
    fun showEdit(input: String, caretPos: Int, gapsBefore: Set<Int>) {
        editInput = input; editCaret = caretPos.coerceIn(0, input.length); editGaps = gapsBefore
        switchTo(Mode.EDIT)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val a = fade.value
        if (fade.running) postInvalidateOnAnimation()
        if (scroller.computeScrollOffset()) { scrollX0 = scroller.currX.toFloat(); postInvalidateOnAnimation() }
        tp.typeface = Ui.regular
        when (mode) {
            Mode.TOOLBAR -> drawToolbar(c, a)
            Mode.CANDIDATES, Mode.PREDICTIONS -> drawCandidates(c, a)
            Mode.CLIP -> drawClip(c, a)
            Mode.MATH -> drawMath(c, a)
            Mode.EDIT -> drawEdit(c, a)
            Mode.MESSAGE -> {
                tp.color = theme.hint; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.BODY); tp.textAlign = Paint.Align.CENTER
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
        val list = tools
        val slot = width / list.size.toFloat()
        val cy = height / 2f
        val rad = minOf(dp(18f), slot / 2 - dp(4f))
        list.forEachIndexed { i, id ->
            val cx = slot * i + slot / 2
            val active = id == activeTool
            // every button sits on a soft disc; the open panel's button is filled with the accent
            bg.color = when {
                active -> if (pressedTool == id) theme.accentPressed else theme.accent
                pressedTool == id -> theme.softPressed
                else -> theme.soft
            }
            bg.alpha = (bg.alpha * a).toInt()
            c.drawCircle(cx, cy, rad, bg)
            val ink = if (active) theme.onAccent else if (id == "ai") theme.accent else theme.inkSoft
            val glyph = TOOL_GLYPHS[id]
            if (glyph != null) {
                tp.textAlign = Paint.Align.CENTER; tp.typeface = Ui.medium; tp.textSize = sp(Ui.TITLE + 1f)
                tp.color = ink; tp.alpha = (255 * a).toInt()
                text(c, glyph, cx, cy)
                tp.typeface = Ui.regular
            } else {
                ip.color = ink; ip.alpha = (255 * a).toInt()
                Icons.draw(c, id, cx, cy, dp(19f), ip, filled = id == "ai")
            }
            hits += RectF(cx - slot / 2, 0f, cx + slot / 2, height.toFloat()) to { listener?.onTool(id) }
        }
    }

    companion object {
        /** Tools that show a text glyph instead of a line icon. */
        val TOOL_GLYPHS = mapOf("symbols" to "§", "math" to "π")
        /** Every tool the toolbar can hold: id to name. */
        val ALL_TOOLS = listOf(
            "ai" to "AI 改写", "emoji" to "表情", "mic" to "语音", "translate" to "翻译", "cursor" to "文字编辑",
            "clipboard" to "剪贴板", "symbols" to "符号", "math" to "学术键盘", "undo" to "撤销", "settings" to "设置",
        )
    }

    private var pressedTool: String? = null

    private fun drawCandidates(c: Canvas, a: Float) {
        val btnW = dp(44f)
        val composing = mode == Mode.CANDIDATES
        preeditRect = RectF()
        if (composing && preedit.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = sp(Ui.SMALL)
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
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.CAPTION); tp.color = theme.accent; tp.alpha = (255 * a).toInt()
            c.drawText(predictionLabel, dp(12f), dp(12f), tp)
        }
        c.save()
        c.clipRect(0f, 0f, width - btnW, height.toFloat())
        candRects.clear()
        var x = dp(4f) - scrollX0
        val top = if (composing || predictionLabel.isNotEmpty()) dp(15f) else dp(4f)
        tp.textAlign = Paint.Align.LEFT
        val size = sp(18f * prefs.candidateScale)
        val noteSize = sp(Ui.CAPTION)
        candidates.forEachIndexed { i, s ->
            tp.textSize = size
            val note = notes.getOrNull(i).orEmpty()
            val tw = tp.measureText(s)
            val nw = if (note.isNotEmpty()) run { tp.textSize = noteSize; tp.measureText(note) } else 0f
            // the note sits above the word, so it adds no width unless it is longer than the word
            val cw = max(tw, nw) + dp(24f)
            val rect = RectF(x, top, x + cw, height.toFloat() - dp(3f))
            candRects += RectF(rect).apply { offset(scrollX0, 0f) }
            if (rect.right > 0 && rect.left < width) {
                if (i == pressedCand) { bg.color = theme.soft; c.drawRoundRect(rect, dp(prefs.keyRadiusDp), dp(prefs.keyRadiusDp), bg) }
                tp.textSize = size
                tp.color = if (i == 0 && composing) theme.accent else theme.ink
                tp.alpha = (255 * a).toInt()
                text(c, s, x + dp(12f) + (cw - dp(24f) - tw) / 2, rect.centerY() + dp(3f))
                if (note.isNotEmpty()) {
                    tp.textSize = noteSize
                    tp.color = if (note.contains('→') || note.contains('⇄')) theme.accent else theme.hint
                    tp.alpha = (255 * a).toInt()
                    c.drawText(note, x + dp(12f) + (cw - dp(24f) - nw) / 2, rect.top + dp(8.5f), tp)
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
        tp.textSize = sp(Ui.SMALL)
        val tagW = tp.measureText(tag)
        tp.textSize = sp(Ui.BODY)
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
        tp.color = theme.accent; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium
        text(c, tag, r.left + dp(14f), r.centerY())
        tp.typeface = Ui.regular
        tp.color = theme.ink; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.BODY)
        text(c, shown, r.left + dp(24f) + tagW, r.centerY())
        hits += r to { listener?.onClipChip(clipText) }
        ip.color = theme.inkSoft
        Icons.draw(c, "close", width - closeW / 2, height / 2f, dp(18f), ip)
        hits += RectF(width - closeW, 0f, width.toFloat(), height.toFloat()) to { listener?.onDismissSuggestions() }
    }

    private val segPos = Tween(0f)
    private val fmtPos = Tween(0f)

    /**
     * A two-way switch whose highlight slides between the options. [strong] fills it with the
     * accent (the main mode), otherwise a light tint (a secondary setting).
     */
    private fun segmented(c: Canvas, box: RectF, names: List<String>, pos: Tween, strong: Boolean, pick: (Int) -> Unit) {
        pill(c, box, theme.soft)
        if (pos.running) postInvalidateOnAnimation()
        val w = box.width() / names.size
        val p = pos.value
        val hl = RectF(box.left + p * w + dp(2f), box.top + dp(2f), box.left + (p + 1) * w - dp(2f), box.bottom - dp(2f))
        pill(c, hl, if (strong) theme.accent else theme.accentSoft)
        names.forEachIndexed { i, name ->
            val r = RectF(box.left + i * w, box.top, box.left + (i + 1) * w, box.bottom)
            val near = (1f - kotlin.math.abs(p - i)).coerceIn(0f, 1f)
            tp.color = blend(theme.inkSoft, if (strong) theme.onAccent else theme.accent, near)
            tp.textSize = sp(if (strong) Ui.BODY else Ui.SMALL)
            tp.typeface = if (near > 0.5f) Ui.medium else Ui.regular
            text(c, name, r.centerX(), r.centerY())
            hits += r to { pick(i) }
        }
        tp.typeface = Ui.regular
    }

    private fun blend(x: Int, y: Int, t: Float): Int {
        fun ch(sh: Int) = (((x shr sh) and 0xFF) * (1 - t) + ((y shr sh) and 0xFF) * t).toInt() shl sh
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /** Small explanations in the number bar go away for good after one tap. */
    private fun hintSeen(key: String) = prefs.sp.getBoolean("seen_$key", false)

    private fun drawMath(c: Canvas, a: Float) {
        tp.textAlign = Paint.Align.CENTER
        // 标准 / 学术 switch, always in the same place
        val sr = RectF(dp(6f), dp(8f), dp(6f) + dp(104f), height - dp(8f))
        segmented(c, sr, listOf("标准", "学术"), segPos, strong = true) { i -> listener?.onNumMode(if (i == 0) "simple" else "sci") }
        val chip = resultChip
        var right = width - dp(6f)
        if (!simpleNum) {
            val fr = RectF(right - dp(96f), dp(8f), right, height - dp(8f))
            segmented(c, fr, listOf("文本", "LaTeX"), fmtPos, strong = false) { i -> listener?.onMathFormat(if (i == 0) "uni" else "tex") }
            right = fr.left - dp(4f)
        }
        val midL = sr.right + dp(6f)
        tp.textAlign = Paint.Align.CENTER
        if (chip != null) {
            tp.textSize = sp(Ui.TITLE)
            val label = "= $chip"
            val w = tp.measureText(label) + dp(28f)
            val r = RectF(midL, dp(7f), midL + w, height - dp(7f))
            pill(c, r, theme.accent)
            tp.color = theme.onAccent
            text(c, label, r.centerX(), r.centerY())
            tp.color = theme.hint; tp.textSize = sp(Ui.SMALL); tp.textAlign = Paint.Align.LEFT
            if (r.right + dp(70f) < right) text(c, "点一下上屏", r.right + dp(8f), height / 2f)
            hits += r to { listener?.onResultChip(chip) }
            return
        }
        // the calculator line (标准) or how the academic keys work (学术), until tapped once
        val key = if (simpleNum) "hint_num" else "hint_sci"
        if (hintSeen(key)) return
        tp.textAlign = Paint.Align.LEFT
        tp.color = theme.hint; tp.textSize = sp(Ui.SMALL)
        val hint = if (simpleNum) "输入算式后点 = 出结果" else "长按看全部 · ⇥ 跳到下一个空位"
        val tw = tp.measureText(hint)
        if (tw < right - midL - dp(8f)) {
            text(c, hint, midL + dp(4f), height / 2f)
            hits += RectF(midL, 0f, midL + tw + dp(12f), height.toFloat()) to {
                prefs.sp.edit().putBoolean("seen_$key", true).apply()
                fade.set(0.6f); fade.animateTo(1f, dur(150))
                invalidate()
            }
        }
    }

    private fun drawEdit(c: Canvas, a: Float) {
        val doneW = dp(56f)
        val size = sp(21f)
        tp.textSize = size; tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.regular
        editPos.clear()
        var x = dp(16f)
        val cy = height / 2f
        val gap = dp(9f)
        editPos += x
        for ((i, ch) in editInput.withIndex()) {
            if (i in editGaps && i > 0) { x += gap; editPos[editPos.size - 1] = x - gap / 2 }
            tp.color = theme.ink; tp.alpha = (255 * a).toInt()
            text(c, ch.toString(), x, cy)
            x += tp.measureText(ch.toString())
            editPos += x
        }
        // caret
        val cx = editPos.getOrElse(editCaret) { x }
        bg.color = theme.accent
        c.drawRoundRect(cx - dp(1.2f), cy - size * 0.62f, cx + dp(1.2f), cy + size * 0.62f, dp(1f), dp(1f), bg)
        tp.typeface = Ui.regular
        val r = RectF(width - doneW - dp(6f), dp(8f), width - dp(6f), height - dp(8f))
        pill(c, r, theme.accent)
        tp.color = theme.onAccent; tp.textSize = sp(Ui.BODY); tp.textAlign = Paint.Align.CENTER
        text(c, "完成", r.centerX(), r.centerY())
        hits += r to { listener?.onEditDone() }
    }

    private fun editCaretAt(x: Float): Int {
        var best = 0; var bd = Float.MAX_VALUE
        editPos.forEachIndexed { i, px -> val d = abs(px - x); if (d < bd) { bd = d; best = i } }
        return best
    }

    // ---- touch ----
    private var downX = 0f
    private var downY = 0f
    private var vDrag = false
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
                downY = e.y; vDrag = false
                if (mode == Mode.EDIT && e.x < width - dp(62f)) {
                    editCaret = editCaretAt(e.x); listener?.onEditCaret(editCaret); invalidate()
                    vt?.recycle(); vt = null
                    return true
                }
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
                if (mode == Mode.EDIT && e.x < width - dp(62f)) {
                    val p = editCaretAt(e.x)
                    if (p != editCaret) { editCaret = p; listener?.onEditCaret(p); invalidate() }
                    return true
                }
                if (onVerticalDrag != null && mode == Mode.TOOLBAR && !vDrag && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                    vDrag = true; pressedTool = null
                    onVerticalDrag?.invoke(0, e.rawY, 0f)
                }
                if (vDrag) { onVerticalDrag?.invoke(1, e.rawY, 0f); return true }
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
                if (mode == Mode.EDIT && e.x < width - dp(62f)) return true
                if (vDrag) {
                    vt?.computeCurrentVelocity(1000)
                    onVerticalDrag?.invoke(2, e.rawY, vt?.yVelocity ?: 0f)
                    vDrag = false; vt?.recycle(); vt = null; invalidate()
                    return true
                }
                when {
                    menuOpen -> {
                        val o = overlay
                        val idx = pressedCand
                        if (o == null || o.pickedByDrag()) {
                            o?.selected()?.let { listener?.onCandidateOption(idx, it) }
                            o?.hideOptions()
                        } else o.stick { pick -> pick?.let { listener?.onCandidateOption(idx, it) } }
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
                        tp.textSize = sp(Ui.SMALL)
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
