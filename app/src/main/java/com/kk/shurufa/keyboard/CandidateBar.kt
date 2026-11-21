package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max

/**
 * The strip above the keys: the toolbar when idle, pinyin and candidates while composing,
 * associations after a word, a paste chip after copying, the number keyboard's bar, or a
 * status line.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, prefs: Prefs) : KbView(context, prefs) {

    interface Listener {
        fun onTool(id: String)
        fun onCandidate(index: Int)
        fun onExpand()
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
        /** The action button of an open query (执行) was tapped. */
        fun onQueryAction() {}
        /** The status line was tapped (it offered an action, like 重试). */
        fun onMessageTap() {}
        /** "press" when a candidate, chip or button is touched, "popup" when a menu opens. */
        fun onFeedback(kind: String) {}
    }

    var listener: Listener? = null
    var overlay: PopupOverlay? = null

    /** Toolbar button whose panel is open: it sits on the sliding accent indicator. */
    var activeTool: String? = null
        set(v) {
            if (field == v) return
            val from = field?.let { tools.indexOf(it) } ?: -1
            val to = v?.let { tools.indexOf(it) } ?: -1
            field = v
            indicator.enabled = Motion.on(prefs)
            when {
                to < 0 -> indicatorAlpha.animateTo(0f, dur(Motion.POP_OUT), Motion.EXIT)
                from < 0 || indicatorAlpha.value < 0.05f -> { indicator.snap(to.toFloat()); indicatorAlpha.animateTo(1f, dur(Motion.POP_IN), Motion.ENTER) }
                else -> { indicator.animateTo(to.toFloat()); indicatorAlpha.animateTo(1f, dur(Motion.POP_IN)) }
            }
            invalidate()
        }

    enum class Mode { TOOLBAR, CANDIDATES, PREDICTIONS, CLIP, MATH, MESSAGE, EDIT, QUERY }

    /** When set, vertical drags on the toolbar resize the open panel (phase 0 start, 1 move, 2 end). */
    var onVerticalDrag: ((phase: Int, rawY: Float, velocityY: Float) -> Unit)? = null

    var mode = Mode.TOOLBAR
        private set
    /** The candidate grid is open: the arrow at the end of the row points up. */
    var expanded = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private var editInput = ""
    private var editCaret = 0
    private var editGaps: Set<Int> = emptySet()
    private val editPos = mutableListOf<Float>()   // x of each caret position 0..len

    private var preedit = ""
    private var caret = -1          // caret position in the preedit, -1 when at the end
    private var candidates: List<String> = emptyList()
    private var notes: List<String> = emptyList()
    private var predictionLabel = ""
    private var mathFormat = "uni"
    private var simpleNum = false
    private var resultChip: String? = null
    private var clipText = ""
    private var clipKind = ""
    private var message = ""

    /** The user's toolbar (settings → 工具栏); 快捷面板 is always last. */
    private val tools get() = prefs.toolbar + "quick"

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ellipsizer = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val fade = Tween(1f)
    private val indicator = Spring(0f, stiffness = 700f, dampingRatio = 0.82f)
    private val indicatorAlpha = Tween(0f)
    private var scrollX0 = 0f
    private val scroller = OverScroller(context)
    private val hits = Hits()
    private val candRects = mutableListOf<RectF>()
    private var candCount = 0
    private val preeditRect = RectF()
    private var contentW = 0f
    private val handler = Handler(Looper.getMainLooper())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(HEIGHT_DP).toInt())
    }

    private fun switchTo(m: Mode) {
        if (m != mode) { fade.set(0f); fade.animateTo(1f, dur(Motion.SWAP), Motion.ENTER) }
        mode = m
        invalidate()
    }

    fun showToolbar() = switchTo(Mode.TOOLBAR)

    /** Updates the candidates. Only the first switch into this mode fades; later updates are instant. */
    fun showCandidates(pre: String, cands: List<String>, annotations: List<String> = emptyList(), caretIndex: Int = -1) {
        val entering = mode != Mode.CANDIDATES
        preedit = pre; candidates = cands; notes = annotations; caret = caretIndex
        if (entering) { fade.set(0f); fade.animateTo(1f, dur(120), Motion.ENTER) }
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

    /** The number keyboard's bar: the 标准/学术 switch first, then the calculator line or the format switch. */
    fun showMath(format: String, simple: Boolean) {
        val keepResult = simple == simpleNum && mode == Mode.MATH
        // the switches slide when they change while the bar stays on screen, and jump otherwise
        val seg = if (simple) 0f else 1f
        val fmt = if (format == "tex") 1f else 0f
        if (mode == Mode.MATH) {
            if (seg != segPos.target) segPos.animateTo(seg, dur(220))
            if (fmt != fmtPos.target) fmtPos.animateTo(fmt, dur(220))
        } else { segPos.set(seg); fmtPos.set(fmt) }
        mathFormat = format; simpleNum = simple
        if (!keepResult) resultChip = null
        switchTo(Mode.MATH)
    }

    fun showResult(text: String?) { resultChip = text; invalidate() }

    /**
     * A status line; with [tappable] the whole bar answers a tap ([Listener.onMessageTap]); with
     * [busy] a thin line under it says the work is still going.
     */
    private var queryLabel = ""
    private var queryText = ""
    private var queryHint = ""
    private var queryAction: String? = null
    private var queryChanged = 0L

    /**
     * A query typed with the keys below (AI 指令, 查找表情): its name small on the pinyin line, and on
     * the words' line a field — the text with a blinking caret, or [hint] while empty — with the
     * [action] button on the right when there is something to act on.
     */
    fun showQuery(label: String, text: String, hint: String, action: String?) {
        if (text != queryText) queryChanged = Motion.now()
        queryLabel = label; queryText = text; queryHint = hint; queryAction = action
        switchTo(Mode.QUERY)
    }

    fun showMessage(text: String, tappable: Boolean = false, busy: Boolean = false) {
        message = text; messageTap = tappable
        if (busy && !messageBusy) busySince = Motion.now()
        messageBusy = busy
        switchTo(Mode.MESSAGE)
    }

    private var messageTap = false
    private var messageBusy = false
    private var busySince = 0L

    /** 拼音编辑: the typed letters, large, with a caret that can be dragged letter by letter. */
    fun showEdit(input: String, caretPos: Int, gapsBefore: Set<Int>) {
        editInput = input; editCaret = caretPos.coerceIn(0, input.length); editGaps = gapsBefore
        switchTo(Mode.EDIT)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val a = fade.value
        var again = fade.running
        if (scroller.computeScrollOffset()) { scrollX0 = scroller.currX.toFloat(); again = true }
        tp.typeface = Ui.regular
        if (a > 0.01f) when (mode) {
            Mode.TOOLBAR -> again = drawToolbar(c, a) || again
            Mode.CANDIDATES, Mode.PREDICTIONS -> drawCandidates(c, a)
            Mode.CLIP -> drawClip(c, a)
            Mode.MATH -> again = drawMath(c, a) || again
            Mode.EDIT -> drawEdit(c, a)
            Mode.QUERY -> drawQuery(c, a)
            Mode.MESSAGE -> {
                tp.color = if (messageTap) theme.accent else theme.hint; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.BODY); tp.textAlign = Paint.Align.CENTER
                if (messageTap) tp.typeface = Ui.medium
                ellipsizer.set(tp)
                drawText(c, android.text.TextUtils.ellipsize(message, ellipsizer, width - dp(32f), android.text.TextUtils.TruncateAt.END), width / 2f, lineCy, tp)
                tp.typeface = Ui.regular
                if (messageTap) hits.add("message", 0f, 0f, width.toFloat(), height.toFloat())
                if (messageBusy) { drawBusy(c, a); again = true }
            }
        }
        if (again) postInvalidateOnAnimation()
    }

    /** An indeterminate line under a status that is still in progress: a short segment gliding across. */
    private fun drawBusy(c: Canvas, a: Float) {
        val track = dp(120f)
        val y = lineCy + dp(13f)
        val left = (width - track) / 2
        bg.color = Colors.fade(theme.divider, a)
        c.drawRoundRect(left, y - dp(1f), left + track, y + dp(1f), dp(1f), dp(1f), bg)
        // one sweep every 1.4 s, eased at both ends
        val t = ((Motion.now() - busySince) % 1400L) / 1400f
        val p = Motion.MOVE.getInterpolation(if (t < 0.5f) t * 2 else 2 - t * 2)
        val seg = track * 0.32f
        val x = left + (track - seg) * p
        bg.color = Colors.fade(theme.accent, a)
        c.drawRoundRect(x, y - dp(1f), x + seg, y + dp(1f), dp(1f), dp(1f), bg)
    }

    /** Returns true while the indicator is still moving. */
    private val lineCy get() = dp(LINE_DP)

    private fun drawToolbar(c: Canvas, a: Float): Boolean {
        val list = tools
        val slot = width / list.size.toFloat()
        val cy = lineCy
        val ia = indicatorAlpha.value
        if (indicator.running) indicator.step()
        // the open panel's button sits on a pill that glides from button to button
        if (ia > 0.01f && activeTool != null) {
            val cx = slot * indicator.value + slot / 2
            val w = minOf(dp(50f), slot - dp(10f)) * (0.85f + 0.15f * ia)
            val h = dp(32f) * (0.85f + 0.15f * ia)
            bg.color = Colors.fade(theme.accentSoft, ia * a)
            c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, bg)
        }
        list.forEachIndexed { i, id ->
            val cx = slot * i + slot / 2
            val active = id == activeTool
            if (pressedTool == id && !active) {
                bg.color = Colors.fade(theme.soft, a)
                val w = minOf(dp(50f), slot - dp(10f)); val h = dp(32f)
                c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, bg)
            }
            val ink = Colors.fade(if (active) theme.accent else theme.inkSoft, a)
            val glyph = TOOL_GLYPHS[id]
            if (glyph != null) {
                tp.textAlign = Paint.Align.CENTER; tp.typeface = Ui.medium; tp.textSize = sp(Ui.TITLE + 1f)
                tp.color = ink
                drawText(c, glyph, cx, cy, tp)
                tp.typeface = Ui.regular
            } else {
                ip.color = ink
                Icons.draw(c, id, cx, cy, dp(Ui.TOOL_ICON), ip, filled = active && id == "ai")
            }
            hits.add("tool:$id", cx - slot / 2, 0f, cx + slot / 2, height.toFloat())
        }
        return indicator.running || indicatorAlpha.running
    }

    companion object {
        const val HEIGHT_DP = 50f
        /**
         * Where everything in the bar is centered: tool icons, words, chips, the end button. The
         * top 16 dp hold the pinyin while typing; keeping every mode on this one line means the
         * words come up exactly where the icons were, and nothing jumps when typing starts.
         */
        const val LINE_DP = 32f
        /** Tools that show a text glyph instead of a line icon. */
        val TOOL_GLYPHS = mapOf("symbols" to "§", "math" to "π")
        /** Every tool the toolbar can hold: id to name. */
        val ALL_TOOLS = listOf(
            "ai" to "AI 改写", "emoji" to "表情", "mic" to "语音", "translate" to "翻译", "cursor" to "文字编辑",
            "clipboard" to "剪贴板", "symbols" to "符号", "math" to "学术键盘", "undo" to "撤销", "settings" to "设置",
        )
        /** The end button of the candidate row (expand, or close). */
        private const val END_DP = 46f
        private val MODES = arrayOf("标准", "学术")
        private val FORMATS = arrayOf("文本", "LaTeX")
    }

    private var pressedTool: String? = null

    private fun drawCandidates(c: Canvas, a: Float) {
        val endW = dp(END_DP)
        val composing = mode == Mode.CANDIDATES
        preeditRect.setEmpty()
        if (composing && preedit.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = sp(Ui.SMALL)
            tp.color = theme.hint
            tp.alpha = (255 * a).toInt()
            val x0 = dp(12f)
            c.drawText(preedit, x0, dp(14f), tp)
            preeditRect.set(0f, 0f, x0 + tp.measureText(preedit) + dp(12f), dp(18f))
            if (caret in 0 until preedit.length) {
                // the pinyin is being edited: show where letters will go
                val cx = x0 + tp.measureText(preedit, 0, caret)
                bg.color = theme.accent
                c.drawRect(cx - dp(0.8f), dp(3.5f), cx + dp(0.8f), dp(16f), bg)
            }
        } else if (!composing && predictionLabel.isNotEmpty()) {
            // 按原拼音 / 查找: on the pinyin line, where what was typed always shows
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.color = theme.accent; tp.alpha = (255 * a).toInt()
            ellipsizer.set(tp)
            c.drawText(android.text.TextUtils.ellipsize(predictionLabel, ellipsizer, width - endW - dp(24f), android.text.TextUtils.TruncateAt.END).toString(), dp(12f), dp(14f), tp)
        }
        c.save()
        c.clipRect(0f, 0f, width - endW, height.toFloat())
        candCount = 0
        var x = dp(4f) - scrollX0
        // one line for the pinyin is always kept, so the words sit at the same height whether they
        // are candidates, predictions, or follow a deletion
        val top = dp(16f)
        val bottom = height - dp(3f)
        tp.textAlign = Paint.Align.LEFT
        val size = sp(Ui.CANDIDATE * prefs.candidateScale)
        val noteSize = sp(Ui.CAPTION)
        val r = dp(prefs.keyRadiusDp)
        for (i in candidates.indices) {
            val s = candidates[i]
            tp.textSize = size
            val note = notes.getOrNull(i).orEmpty()
            val tw = tp.measureText(s)
            val nw = if (note.isNotEmpty()) { tp.textSize = noteSize; tp.measureText(note) } else 0f
            // the note sits above the word, so it adds no width unless it is longer than the word
            val cw = max(tw, nw) + dp(24f)
            val rect = candRect(candCount++).apply { set(x + scrollX0, top, x + scrollX0 + cw, bottom) }
            if (x + cw > 0 && x < width) {
                val cr = tmp.apply { set(rect); offset(-scrollX0, 0f) }
                if (i == pressedCand) { bg.color = theme.soft; c.drawRoundRect(cr, r, r, bg) }
                tp.textSize = size
                tp.color = if (i == 0 && composing) theme.accent else theme.ink
                tp.alpha = (255 * a).toInt()
                drawText(c, s, x + dp(12f) + (cw - dp(24f) - tw) / 2, lineCy, tp)
                if (note.isNotEmpty()) {
                    tp.textSize = noteSize
                    tp.color = if (note.contains('→') || note.contains('⇄')) theme.accent else theme.hint
                    tp.alpha = (255 * a).toInt()
                    c.drawText(note, x + dp(12f) + (cw - dp(24f) - nw) / 2, lineCy - dp(9.5f), tp)
                }
            }
            x += cw
        }
        contentW = x + scrollX0
        c.restore()
        // soft edges where the row goes on past what is shown
        val fw = dp(18f)
        val clipR = width - endW
        if (scrollX0 > 1f) {
            bg.shader = edgeL ?: LinearGradient(0f, 0f, fw, 0f, theme.background, Colors.alpha(theme.background, 0), Shader.TileMode.CLAMP).also { edgeL = it }
            c.drawRect(0f, top, fw, height.toFloat(), bg); bg.shader = null
        }
        if (scrollX0 < scrollMax - 1f) {
            if (edgeAt != clipR) { edgeR = null; edgeAt = clipR }
            bg.shader = edgeR ?: LinearGradient(clipR - fw, 0f, clipR, 0f, Colors.alpha(theme.background, 0), theme.background, Shader.TileMode.CLAMP).also { edgeR = it }
            c.drawRect(clipR - fw, top, clipR, height.toFloat(), bg); bg.shader = null
        }
        val ex = width - endW / 2
        ip.color = Colors.fade(theme.inkSoft, a)
        if (composing) Icons.draw(c, if (expanded) "expand" else "collapse", ex, lineCy, dp(20f), ip)
        else Icons.draw(c, "close", ex, lineCy, dp(18f), ip)
        bg.color = Colors.fade(theme.divider, a)
        c.drawRect(width - endW, lineCy - dp(11f), width - endW + dp(1f), lineCy + dp(11f), bg)
        hits.add(if (composing) "expand" else "dismiss", width - endW, 0f, width.toFloat(), height.toFloat())
    }

    private fun drawQuery(c: Canvas, a: Float) {
        val endW = dp(END_DP)
        // the name of what is being typed, on the pinyin line
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium
        tp.color = theme.accent; tp.alpha = (255 * a).toInt()
        c.drawText(queryLabel, dp(12f), dp(14f), tp)
        tp.typeface = Ui.regular
        // the action, then the close button
        var right = width - endW
        val act = queryAction
        if (act != null) {
            val w = chipWidth(act)
            val r = tmp.apply { set(right - w - dp(4f), lineCy - dp(Ui.CHIP_H / 2), right - dp(4f), lineCy + dp(Ui.CHIP_H / 2)) }
            drawChip(c, r, act, ChipStyle.PRIMARY, pressed = pressedQuery, alpha = a)
            hits.add("query", r, slop = dp(4f))
            right = r.left - dp(8f)
        }
        // the field: text and caret, or the hint after the caret
        val x0 = dp(12f)
        val empty = queryText.isEmpty()
        tp.textSize = sp(if (empty) Ui.BODY else Ui.TITLE)
        tp.color = if (empty) theme.hint else theme.ink; tp.alpha = (255 * a).toInt()
        ellipsizer.set(tp)
        val room = (right - x0 - dp(10f)).coerceAtLeast(0f)
        val shown = android.text.TextUtils.ellipsize(if (empty) queryHint else queryText, ellipsizer, room,
            if (empty) android.text.TextUtils.TruncateAt.END else android.text.TextUtils.TruncateAt.START).toString()
        val textX = if (empty) x0 + dp(6f) else x0
        drawText(c, shown, textX, lineCy, tp)
        val since = Motion.now() - queryChanged
        if ((since / 530) % 2 == 0L) {
            val cx = if (empty) x0 else x0 + tp.measureText(shown) + dp(1.5f)
            bg.color = Colors.fade(theme.accent, a)
            val h = sp(Ui.TITLE) * 0.62f
            c.drawRect(cx, lineCy - h, cx + dp(1.6f), lineCy + h, bg)
        }
        postInvalidateDelayed(530 - since % 530)
        ip.color = Colors.fade(theme.inkSoft, a)
        Icons.draw(c, "close", width - endW / 2, lineCy, dp(18f), ip)
        bg.color = Colors.fade(theme.divider, a)
        c.drawRect(width - endW, lineCy - dp(11f), width - endW + dp(1f), lineCy + dp(11f), bg)
        hits.add("dismiss", width - endW, 0f, width.toFloat(), height.toFloat())
    }

    private var pressedQuery = false

    private var edgeL: LinearGradient? = null
    private var edgeR: LinearGradient? = null
    private var edgeAt = 0f

    override fun onThemeChanged() { edgeL = null; edgeR = null }

    private fun candRect(i: Int): RectF {
        while (candRects.size <= i) candRects += RectF()
        return candRects[i]
    }

    private val tmp = RectF()

    private fun drawClip(c: Canvas, a: Float) {
        val closeW = dp(END_DP)
        val label = clipText.replace('\n', ' ').trim()
        val tag = "粘贴" + if (clipKind.isNotEmpty()) " · $clipKind" else ""
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium
        val tagW = tp.measureText(tag)
        tp.textSize = sp(Ui.BODY); tp.typeface = Ui.regular
        val maxText = width - 2 * closeW - tagW - dp(40f)
        var shown = label
        if (tp.measureText(shown) > maxText) {
            var end = shown.length
            val ell = tp.measureText("…")
            while (end > 1 && tp.measureText(shown, 0, end) + ell > maxText) end--
            shown = shown.substring(0, end) + "…"
        }
        val w = tagW + tp.measureText(shown) + dp(40f)
        // centered in the bar, between equal margins
        val left = (width - w) / 2
        val r = tmp.apply { set(left, lineCy - dp(Ui.CHIP_H / 2), left + w, lineCy + dp(Ui.CHIP_H / 2)) }
        bg.color = Colors.fade(if (pressedClip) Colors.blend(theme.accentSoft, theme.accent, 0.2f) else theme.accentSoft, a)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
        tp.color = theme.accent; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium
        drawText(c, tag, r.left + dp(14f), r.centerY(), tp)
        tp.typeface = Ui.regular
        tp.color = theme.ink; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.BODY)
        drawText(c, shown, r.left + dp(26f) + tagW, r.centerY(), tp)
        hits.add("clip", r)
        ip.color = Colors.fade(theme.inkSoft, a)
        Icons.draw(c, "close", width - closeW / 2, lineCy, dp(18f), ip)
        hits.add("dismiss", width - closeW, 0f, width.toFloat(), height.toFloat())
    }

    private var pressedClip = false
    private val segPos = Tween(0f)
    private val fmtPos = Tween(0f)

    /**
     * A two-way switch whose highlight slides between the options. [strong] fills it with the
     * accent (the main mode), otherwise a light tint (a secondary setting).
     */
    private fun segmented(c: Canvas, box: RectF, names: Array<String>, pos: Tween, strong: Boolean, id: String, a: Float) {
        bg.color = Colors.fade(theme.soft, a)
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, bg)
        val w = box.width() / names.size
        val p = pos.value
        val hl = tmp.apply { set(box.left + p * w + dp(3f), box.top + dp(3f), box.left + (p + 1) * w - dp(3f), box.bottom - dp(3f)) }
        bg.color = Colors.fade(if (strong) theme.accent else theme.accentSoft, a)
        c.drawRoundRect(hl, hl.height() / 2, hl.height() / 2, bg)
        tp.textAlign = Paint.Align.CENTER
        names.forEachIndexed { i, name ->
            val near = (1f - abs(p - i)).coerceIn(0f, 1f)
            tp.color = Colors.fade(Colors.blend(theme.inkSoft, if (strong) theme.onAccent else theme.accent, near), a)
            tp.textSize = sp(if (strong) Ui.BODY else Ui.SMALL + 0.5f)
            tp.typeface = if (near > 0.5f) Ui.medium else Ui.regular
            val l = box.left + i * w
            drawText(c, name, l + w / 2, box.centerY(), tp)
            hits.add("$id:$i", l, box.top, l + w, box.bottom)
        }
        tp.typeface = Ui.regular
    }

    /** Small explanations in the number bar go away for good after one tap. */
    private fun hintSeen(key: String) = prefs.state.getBoolean("seen_$key", false)

    private val segBox = RectF()
    private val fmtBox = RectF()

    private fun drawMath(c: Canvas, a: Float): Boolean {
        // 标准 / 学术 switch, always in the same place
        val h = dp(Ui.CHIP_H + 2f)
        val top = (height - h) / 2
        segBox.set(dp(8f), top, dp(8f) + dp(108f), top + h)
        segmented(c, segBox, MODES, segPos, strong = true, id = "seg", a = a)
        val chip = resultChip
        var right = width - dp(8f)
        if (!simpleNum) {
            fmtBox.set(right - dp(100f), top, right, top + h)
            segmented(c, fmtBox, FORMATS, fmtPos, strong = false, id = "fmt", a = a)
            right = fmtBox.left - dp(6f)
        }
        val midL = segBox.right + dp(8f)
        if (chip != null) {
            tp.textSize = sp(Ui.TITLE); tp.typeface = Ui.medium
            val label = "= $chip"
            val w = tp.measureText(label) + dp(28f)
            val r = tmp.apply { set(midL, top, midL + w, top + h) }
            bg.color = Colors.fade(theme.accent, a)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
            tp.color = theme.onAccent; tp.alpha = (255 * a).toInt(); tp.textAlign = Paint.Align.CENTER
            drawText(c, label, r.centerX(), r.centerY(), tp)
            tp.typeface = Ui.regular
            hits.add("result", r)
            tp.color = theme.hint; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.SMALL); tp.textAlign = Paint.Align.LEFT
            if (r.right + dp(74f) < right) drawText(c, "点一下上屏", r.right + dp(10f), height / 2f, tp)
            return segPos.running || fmtPos.running
        }
        // the calculator line (标准) or how the academic keys work (学术), until tapped once
        val key = if (simpleNum) "hint_num" else "hint_sci"
        if (!hintSeen(key)) {
            tp.textAlign = Paint.Align.LEFT
            tp.color = theme.hint; tp.alpha = (255 * a).toInt(); tp.textSize = sp(Ui.SMALL)
            val hint = if (simpleNum) "输入算式后点 = 出结果" else "长按看全部 · ⇥ 跳到下一个空位"
            val tw = tp.measureText(hint)
            if (tw < right - midL - dp(8f)) {
                drawText(c, hint, midL + dp(4f), height / 2f, tp)
                hits.add("hint:$key", midL, 0f, midL + tw + dp(12f), height.toFloat())
            }
        }
        return segPos.running || fmtPos.running
    }

    private fun drawEdit(c: Canvas, a: Float) {
        val size = sp(21f)
        tp.textSize = size; tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.regular
        editPos.clear()
        var x = dp(16f)
        val cy = lineCy
        val gap = dp(9f)
        editPos += x
        tp.color = theme.ink; tp.alpha = (255 * a).toInt()
        for ((i, ch) in editInput.withIndex()) {
            if (i in editGaps && i > 0) { x += gap; editPos[editPos.size - 1] = x - gap / 2 }
            val s = ch.toString()
            drawText(c, s, x, cy, tp)
            x += tp.measureText(s)
            editPos += x
        }
        // caret
        val cx = editPos.getOrElse(editCaret) { x }
        bg.color = Colors.fade(theme.accent, a)
        c.drawRoundRect(cx - dp(1.2f), cy - size * 0.62f, cx + dp(1.2f), cy + size * 0.62f, dp(1f), dp(1f), bg)
        val bw = chipWidth("完成")
        val bh = dp(Ui.BUTTON_H)
        val r = tmp.apply { set(width - bw - dp(8f), cy - bh / 2, width - dp(8f), cy + bh / 2) }
        drawChip(c, r, "完成", ChipStyle.PRIMARY, pressed = pressedDone, alpha = a)
        hits.add("done", r)
    }

    private var pressedDone = false

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
    private var pressedCand = -1
    private var menuOpen = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** Where the pinyin-edit area ends (the 完成 button is to its right). */
    private val editLimit get() = width - chipWidth("完成") - dp(16f)

    private val longRunnable = Runnable {
        val i = pressedCand
        if (i < 0 || dragging || i >= candCount) return@Runnable
        val opts = listener?.candidateOptions(i).orEmpty()
        if (opts.isEmpty()) return@Runnable
        locateIn(overlay)
        val r = RectF(candRects[i]).apply { offset(-scrollX0 + layerOffset[0], layerOffset[1]) }
        overlay?.showOptions(RectF(r.left, r.bottom + r.height(), r.right, r.bottom + r.height() * 2), r.height(), opts,
            downX + layerOffset[0], fingerY = downY + layerOffset[1])
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

    private fun candidateAt(x: Float, y: Float): Int {
        for (i in 0 until candCount) if (candRects[i].contains(x + scrollX0, y)) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        val list = mode == Mode.CANDIDATES || mode == Mode.PREDICTIONS
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downY = e.y; vDrag = false
                if (mode == Mode.EDIT && e.x < editLimit) {
                    editCaret = editCaretAt(e.x); listener?.onEditCaret(editCaret); invalidate()
                    vt?.recycle(); vt = null
                    return true
                }
                downX = e.x; lastX = e.x; dragging = false; menuOpen = false
                rawScroll = scrollX0
                pressedCand = if (list && e.x < width - dp(END_DP) && !preeditRect.contains(e.x, e.y)) candidateAt(e.x, e.y) else -1
                if (pressedCand >= 0 && mode == Mode.CANDIDATES) handler.postDelayed(longRunnable, 450)
                val hit = hits.at(e.x, e.y)
                pressedTool = hit?.takeIf { mode == Mode.TOOLBAR && it.startsWith("tool:") }?.removePrefix("tool:")
                pressedClip = hit == "clip"
                pressedDone = hit == "done"
                pressedQuery = hit == "query"
                if (pressedCand >= 0 || hit != null || preeditRect.contains(e.x, e.y)) listener?.onFeedback("press")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.EDIT && e.x < editLimit) {
                    val p = editCaretAt(e.x)
                    if (p != editCaret) { editCaret = p; listener?.onEditCaret(p); invalidate() }
                    return true
                }
                if (onVerticalDrag != null && mode == Mode.TOOLBAR && !vDrag && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                    vDrag = true; pressedTool = null; invalidate()
                    onVerticalDrag?.invoke(0, e.rawY, 0f)
                }
                if (vDrag) { onVerticalDrag?.invoke(1, e.rawY, 0f); return true }
                if (menuOpen) {
                    locateIn(overlay)
                    overlay?.moveSelection(e.x + layerOffset[0], e.y + layerOffset[1])
                    return true
                }
                if (!dragging && abs(e.x - downX) > slop && list) {
                    dragging = true; pressedCand = -1; handler.removeCallbacks(longRunnable)
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
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                if (mode == Mode.EDIT && e.x < editLimit) return true
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
                    else -> hits.at(e.x, e.y)?.let { tap(it) }
                }
                pressedCand = -1; pressedTool = null; pressedClip = false; pressedDone = false; pressedQuery = false
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (menuOpen) { overlay?.hideOptions(); menuOpen = false }
                if (dragging) { scroller.springBack(scrollX0.toInt(), 0, 0, scrollMax.toInt(), 0, 0); postInvalidateOnAnimation() }
                pressedCand = -1; pressedTool = null; pressedClip = false; pressedDone = false; pressedQuery = false
                vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }

    private fun tap(id: String) {
        val l = listener ?: return
        when {
            id.startsWith("tool:") -> l.onTool(id.removePrefix("tool:"))
            id == "expand" -> l.onExpand()
            id == "dismiss" -> l.onDismissSuggestions()
            id == "query" -> l.onQueryAction()
            id == "clip" -> l.onClipChip(clipText)
            id == "seg:0" -> l.onNumMode("simple")
            id == "seg:1" -> l.onNumMode("sci")
            id == "fmt:0" -> l.onMathFormat("uni")
            id == "fmt:1" -> l.onMathFormat("tex")
            id == "result" -> resultChip?.let { l.onResultChip(it) }
            id == "done" -> l.onEditDone()
            id == "message" -> l.onMessageTap()
            id.startsWith("hint:") -> {
                prefs.state.edit().putBoolean("seen_" + id.removePrefix("hint:"), true).apply()
                fade.set(0.6f); fade.animateTo(1f, dur(Motion.SWAP))
            }
        }
    }
}
