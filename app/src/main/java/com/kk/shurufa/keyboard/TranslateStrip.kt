package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs

/**
 * 小K's strip above the toolbar, for 改写 · 翻译 · 聊天 alike, all laid out the way 翻译 always
 * was: two chips at the top left, what it does (改写 ▾) and how (润色 ▾, 中文 → English ▾), the
 * engine and ✕ on the right; the text it works on (typed with the keyboard below, or brought in
 * with 一键插入), a line, and the answer with its one button beside it.
 *
 * It grows when it needs to (a list open, a long answer) and shrinks back, smoothly; a change of
 * what it does crossfades the content.
 */
@SuppressLint("ViewConstructor")
class TranslateStrip(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** [which] is "mode", "opt" or "engine". */
    var onPick: ((which: String, id: String) -> Unit)? = null
    var onSwap: (() -> Unit)? = null
    var onEngine: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onButton: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null
    /** 一键插入: fills the empty box with [insertable] (what was copied, or what is in the field). */
    var onInsert: (() -> Unit)? = null
    var insertable = ""
    /** ✕ in the text line: empties it. */
    var onClear: (() -> Unit)? = null
    /** The text was tapped at [offset]: the caret goes there. */
    var onCaret: ((offset: Int) -> Unit)? = null
    /** Where the caret is in [source] (-1: at its end). */
    var caret = -1

    /** "translate", "rewrite" or "chat". */
    var mode = "translate"
    /** The first chip: what the strip does (改写 · 翻译 · 聊天). */
    var modeLabel = "翻译"
    /** The second chip: how (润色, 中文 → English, 小K). */
    var optLabel = ""
    var showSwap = true
    var placeholder = "输入要翻译的文字"
    /** A small tag at the end of the text line (改写 by instruction: what it will change). */
    var sourceTag = ""
    var resultHint = "译文会显示在这里"
    var busyText = "翻译中…"
    /** The answer's button, (id, label), when there is one. */
    var action: Pair<String, String>? = null

    var engineName = "离线"
    var source = ""
    var sourceIsSelection = false
    var result = ""
    var status = ""
    var busy = false
    /** Engines offered by the engine chip; the current one is [engine]. */
    var engines: List<PickList.Item>
        get() = pick.items
        set(v) { pick.items = v }
    var engine: String
        get() = pick.current
        set(v) { pick.current = v }
    var modeItems: List<PickList.Item>
        get() = modes.items
        set(v) { modes.items = v }
    var optItems: List<PickList.Item>
        get() = opts.items
        set(v) { opts.items = v }
    var optCurrent: String
        get() = opts.current
        set(v) { opts.current = v }
    private val pick = PickList(this).apply { rowDp = 36f }
    private val modes = PickList(this).apply { rowDp = 40f }
    private val opts = PickList(this).apply { rowDp = 38f }
    private var picking: String? = null   // "mode" | "opt" | "engine"

    /** Spans set on [result] by the host (the changes of 改写, marked). */
    var marks: ((CharSequence) -> CharSequence)? = null

    /** The texts changed: lay them out again. */
    fun refresh() {
        srcLayout = null; resLayout = null; caretSince = SystemClock.uptimeMillis()
        if (isLaidOut && heightT.target != needFor(width)) requestLayout()
        invalidate()
    }

    /** What it does changed: the content crossfades into the new one. */
    fun switched() {
        picking = null; held = 0f; lastAnswer = ""
        if (Motion.on(prefs)) { swapT.set(0f); swapT.animateTo(1f, dur(240), Motion.ENTER) } else swapT.set(1f)
        refresh()
    }

    fun closeLists() { if (picking != null) { picking = null; refresh() } }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var srcLayout: StaticLayout? = null
    private var resLayout: StaticLayout? = null
    private val hits = Hits()
    private var pressed: String? = null
    private val r = RectF()
    private val area = RectF()
    /** The caret blinks from the last change: two redraws a second, none while hidden. */
    private var caretSince = 0L
    private val swapT = Tween(1f)
    /** The strip's height in px, moving toward what its content needs. */
    private val heightT = Tween(-1f)

    override fun onThemeChanged() { srcLayout = null; resLayout = null }

    private val baseH get() = dp(136f)
    private val inputH get() = dp(48f)
    private val headH get() = dp(6f) + dp(8f) + dp(30f) + dp(4f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // the height its content needs, worked out before it is drawn, and moved toward smoothly
        val h = needFor(w)
        if (heightT.value < 0f || !isLaidOut || !Motion.on(prefs)) heightT.set(h)
        else if (heightT.target != h) heightT.animateTo(h, dur(260), Motion.ENTER)
        setMeasuredDimension(w, heightT.value.toInt())
    }

    private fun textW(w: Int): Int {
        val bw = action?.let { chipWidth(it.second, textSp = Ui.SMALL + 1f) + dp(10f) } ?: 0f
        return (w - 2 * dp(Ui.EDGE) - dp(28f) - bw).toInt().coerceAtLeast(1)
    }

    /** The last answer shown: while a new one is on its way it stays, faded, rather than blinking away. */
    private var lastAnswer = ""
    private fun answerText() = when { result.isNotEmpty() -> result.also { lastAnswer = it }; busy && lastAnswer.isNotEmpty() -> lastAnswer; busy -> busyText; status.isNotEmpty() -> status; else -> resultHint.also { lastAnswer = "" } }

    /** The header, the text line, and the answer's lines (two at least, up to a few). */
    /** The tallest the text has needed since it was last emptied: typing does not make it jump up and down. */
    private var held = 0f

    private fun needFor(w: Int): Float {
        if (picking == "engine") return dp(250f)
        if (source.isEmpty() && result.isEmpty()) held = 0f
        body.textSize = sp(Ui.BODY)
        val res = answerText()
        val lines = StaticLayout.Builder.obtain(res, 0, res.length, body, textW(w)).setMaxLines(MAX_LINES).setLineSpacing(0f, 1.05f).build().lineCount
        val need = headH + inputH + dp(1f) + lines.coerceAtLeast(2) * body.fontSpacing * 1.05f + dp(18f) + dp(4f)
        held = maxOf(held, need)
        return maxOf(baseH, held)
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        if (heightT.running) requestLayout()
        val pad = dp(Ui.EDGE)
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        val card = area.apply { set(pad, dp(6f), width - pad, height - dp(4f)) }
        bg.color = t.surface
        c.drawRoundRect(card, rad, rad, bg)
        val cardL = card.left; val cardR = card.right; val cardT = card.top; val cardB = card.bottom
        // header: what ▾ how ▾ … engine ×. The right end is placed first; the how-chip gets what
        // is left, shortened if it must be
        val hy = cardT + dp(8f); val hh = dp(30f)
        r.set(cardR - dp(6f) - hh - dp(6f), hy, cardR - dp(6f), hy + hh)
        // 收起 (a chevron down): not a second ✕ beside the text line's
        drawChip(c, r, "", ChipStyle.GHOST, pressed = pressed == "close", icon = "chevron")
        hits.add("close", r, slop = dp(4f))
        val engineLabel = fit(engineName, (cardR - cardL) * 0.28f, Ui.SMALL)
        val ew = chipWidth(engineLabel, chevron = true, textSp = Ui.SMALL)
        r.set(r.left - dp(4f) - ew, hy + dp(2f), r.left - dp(4f), hy + hh - dp(2f))
        drawChip(c, r, engineLabel, if (picking == "engine") ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "engine", chevron = true, textSp = Ui.SMALL)
        hits.add("engine", r, slop = dp(4f))
        val engineLeft = r.left
        var x = cardL + dp(8f)
        val mw = chipWidth(modeLabel, chevron = true, textSp = Ui.SMALL + 1f)
        r.set(x, hy, x + mw, hy + hh)
        drawChip(c, r, modeLabel, ChipStyle.ACTIVE, pressed = pressed == "mode" || picking == "mode", chevron = true, textSp = Ui.SMALL + 1f)
        hits.add("mode", r, slop = dp(4f))
        x = r.right + dp(6f)
        if (optLabel.isNotEmpty()) {
            val swapW = if (showSwap) hh + dp(8f) else 0f
            val room = engineLeft - dp(8f) - x - swapW
            val label = fit(optLabel, room, Ui.SMALL + 1f)
            val ow = chipWidth(label, chevron = true, textSp = Ui.SMALL + 1f)
            r.set(x, hy, x + ow, hy + hh)
            drawChip(c, r, label, if (picking == "opt") ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "opt", chevron = true, textSp = Ui.SMALL + 1f)
            hits.add("opt", r, slop = dp(4f))
            if (showSwap) {
                r.set(r.right + dp(2f), hy, r.right + dp(2f) + hh + dp(6f), hy + hh)
                drawChip(c, r, "", ChipStyle.GHOST, pressed = pressed == "swap", icon = "swap")
                hits.add("swap", r, slop = dp(4f))
            }
        }
        val innerL = cardL + dp(14f); val innerR = cardR - dp(14f)
        val innerT = hy + hh + dp(4f); val innerB = cardB - dp(8f)
        val listArea = r.apply { set(cardL + dp(4f), hy + hh + dp(2f), cardR - dp(4f), cardB - dp(4f)) }
        if (picking == "engine") { if (pick.draw(c, listArea, t, rad)) postInvalidateOnAnimation(); return }
        // the content, crossfading in when what it does changed
        val k = swapT.value
        val save = if (k < 1f) c.saveLayerAlpha(card.left, innerT, card.right, card.bottom, (255 * k).toInt()) else c.save()
        if (k < 1f) { c.translate(0f, (1f - k) * dp(8f)); postInvalidateOnAnimation() }
        drawContent(c, innerL, innerT, innerR, innerB)
        c.restoreToCount(save)
    }

    private fun drawContent(c: Canvas, innerL: Float, innerT: Float, innerR: Float, innerB: Float) {
        val t = theme
        val btnH = dp(Ui.CHIP_H)
        body.textSize = sp(Ui.BODY)
        // the text line: two lines at most, its end in view
        val inputH = inputH
        if (picking == "mode" || picking == "opt") {
            // the choices, in one row where the text line was, sliding sideways; the strip keeps its height
            drawChoices(c, innerL, innerT, innerR, inputH, if (picking == "mode") modes else opts)
        } else drawText(c, innerL, innerT, innerR, inputH, btnH)
        bg.color = t.divider
        c.drawRect(innerL, innerT + inputH, innerR, innerT + inputH + dp(0.8f), bg)
        drawAnswer(c, innerL, innerT, innerR, innerB, inputH, btnH)
    }

    private val chipRow = RectF()
    private var chipScroll = 0f
    private var chipMax = 0f

    private fun drawChoices(c: Canvas, l: Float, top: Float, right: Float, h: Float, list: PickList) {
        chipRow.set(l - dp(4f), top, right + dp(4f), top + h)
        val ch = dp(30f); val gap = dp(6f)
        var x = l
        val y = top + (h - ch) / 2
        c.save(); c.clipRect(chipRow)
        for (item in list.items) {
            val label = item.name
            val w = chipWidth(label, textSp = Ui.SMALL + 1f)
            r.set(x - chipScroll, y, x - chipScroll + w, y + ch)
            if (r.right > chipRow.left && r.left < chipRow.right) {
                drawChip(c, r, label, if (item.id == list.current || item.sub == "当前") ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "pick:${item.id}", textSp = Ui.SMALL + 1f)
                hits.add("pick:${item.id}", maxOf(r.left, chipRow.left), r.top, minOf(r.right, chipRow.right), r.bottom)
            }
            x += w + gap
        }
        c.restore()
        chipMax = maxOf(0f, x - gap - right)
        chipScroll = chipScroll.coerceIn(0f, chipMax)
        // a soft edge where more lies beyond
        if (chipScroll < chipMax - 1f) { bg.shader = android.graphics.LinearGradient(right - dp(24f), 0f, right + dp(4f), 0f, Colors.alpha(theme.surface, 0), theme.surface, android.graphics.Shader.TileMode.CLAMP); c.drawRect(right - dp(24f), top, right + dp(4f), top + h, bg); bg.shader = null }
        if (chipScroll > 1f) { bg.shader = android.graphics.LinearGradient(l - dp(4f), 0f, l + dp(20f), 0f, theme.surface, Colors.alpha(theme.surface, 0), android.graphics.Shader.TileMode.CLAMP); c.drawRect(l - dp(4f), top, l + dp(20f), top + h, bg); bg.shader = null }
    }

    private fun drawText(c: Canvas, innerL: Float, innerT: Float, innerR: Float, inputH: Float, btnH: Float) {
        val t = theme
        val clearW = if (source.isNotEmpty() && !sourceIsSelection) dp(28f) else 0f
        val tagW = when {
            source.isEmpty() && !sourceIsSelection && insertable.isNotEmpty() -> chipWidth("一键插入", icon = "clipboard", textSp = Ui.SMALL + 1f)
            sourceTag.isNotEmpty() -> tagWidth(sourceTag)
            else -> 0f
        }
        val srcW = (innerR - innerL - (if (tagW > 0f) tagW + dp(8f) else 0f) - clearW - dp(6f)).toInt().coerceAtLeast(1)
        val src = source.ifEmpty { placeholder }
        // the whole text, laid out once; two lines of it in view, the caret's among them
        val sl = srcLayout ?: StaticLayout.Builder.obtain(src, 0, src.length, body.apply { color = if (source.isEmpty()) t.hint else t.inkSoft; typeface = Ui.regular }, srcW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).build().also { srcLayout = it }
        val at = if (source.isEmpty()) 0 else if (caret in 0..source.length) caret else source.length
        val caretLine = sl.getLineForOffset(at)
        val firstLine = (caretLine - 1).coerceIn(0, maxOf(0, sl.lineCount - 2))
        val shownH = (sl.getLineBottom(minOf(sl.lineCount - 1, firstLine + 1)) - sl.getLineTop(firstLine)).toFloat()
        val srcTop = innerT + (inputH - shownH) / 2 - sl.getLineTop(firstLine)
        // the placeholder sits just after the caret, not under it
        val srcX = innerL + if (source.isEmpty() && !sourceIsSelection) dp(5f) else 0f
        srcBox.set(innerL, innerT, innerL + srcW + dp(6f), innerT + inputH); srcOrigin.set(srcX, srcTop)
        c.save(); c.clipRect(innerL - dp(2f), innerT + dp(2f), innerL + srcW + dp(8f), innerT + inputH - dp(2f)); c.translate(srcX, srcTop); sl.draw(c); c.restore()
        if (!sourceIsSelection) {
            // a caret where typing goes, blinking at half a second
            val since = SystemClock.uptimeMillis() - caretSince
            val on = (since / 530) % 2 == 0L
            postInvalidateDelayed(530 - since % 530)
            if (on) {
                val cx = innerL + (if (source.isEmpty()) 0f else sl.getPrimaryHorizontal(at) + if (at == source.length) dp(1.5f) else 0f)
                bg.color = t.accent
                c.drawRect(cx, srcTop + sl.getLineTop(caretLine) + dp(2f), cx + dp(1.6f), srcTop + sl.getLineBottom(caretLine) - dp(2f), bg)
            }
            if (!source.isEmpty()) hits.add("src", srcBox)
        }
        if (clearW > 0f) {
            r.set(innerR - (if (tagW > 0f) tagW + dp(8f) else 0f) - clearW, innerT + (inputH - dp(28f)) / 2, innerR - (if (tagW > 0f) tagW + dp(8f) else 0f), innerT + (inputH + dp(28f)) / 2)
            // the text line's own clear: a small filled disc with a cross, as search boxes have
            val cx = r.centerX(); val cy = r.centerY(); val rad = dp(9f)
            bg.color = Colors.fade(t.hint, if (pressed == "clear") 0.55f else 0.32f)
            c.drawCircle(cx, cy, rad, bg)
            xPaint.color = t.surface; xPaint.strokeWidth = dp(1.6f)
            val d = dp(3.6f)
            c.drawLine(cx - d, cy - d, cx + d, cy + d, xPaint); c.drawLine(cx - d, cy + d, cx + d, cy - d, xPaint)
            hits.add("clear", r, slop = dp(6f))
        }
        if (source.isEmpty() && !sourceIsSelection && insertable.isNotEmpty()) {
            // 一键插入: the box is empty, and there is something to work on at hand
            r.set(innerR - tagW, innerT + (inputH - btnH) / 2, innerR, innerT + (inputH + btnH) / 2)
            drawChip(c, r, "一键插入", ChipStyle.SOFT, pressed = pressed == "insert", icon = "clipboard", textSp = Ui.SMALL + 1f)
            hits.add("insert", r, slop = dp(6f))
        } else if (sourceTag.isNotEmpty()) drawTag(c, sourceTag, innerR, innerT + inputH / 2)
    }

    private fun drawAnswer(c: Canvas, innerL: Float, innerT: Float, innerR: Float, innerB: Float, inputH: Float, btnH: Float) {
        val t = theme
        // the answer beside its one button, as many lines as it needs (up to a few)
        val resT = innerT + inputH + dp(1f)
        val res = answerText()
        val has = result.isNotEmpty()
        val stale = !has && busy && res == lastAnswer && lastAnswer.isNotEmpty()
        val rl = resLayout ?: layout(res, textW(width), if (has) t.ink else t.hint, TextUtils.TruncateAt.END, MAX_LINES, medium = (has || stale) && mode == "translate", marked = has).also { resLayout = it }
        val room = innerB - resT
        c.save(); c.clipRect(innerL, resT, innerR, innerB); c.translate(innerL, resT + ((room - rl.height) / 2).coerceAtLeast(dp(6f))); rl.draw(c); c.restore()
        action?.let { (id, label) ->
            val w = chipWidth(label, textSp = Ui.SMALL + 1f)
            val top = resT + ((room - btnH) / 2).coerceAtMost(dp(30f))
            r.set(innerR - w, top, innerR, top + btnH)
            drawChip(c, r, label, ChipStyle.PRIMARY, pressed = pressed == "b:$id", textSp = Ui.SMALL + 1f)
            hits.add("b:$id", r, slop = dp(6f))
        }
    }

    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val srcBox = RectF()
    private val srcOrigin = android.graphics.PointF()
    private val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private fun tagWidth(s: String): Float { tagPaint.textSize = sp(Ui.SMALL - 1f); return tagPaint.measureText(s) + dp(14f) }
    private fun drawTag(c: Canvas, s: String, right: Float, cy: Float) {
        val w = tagWidth(s); val h = dp(20f)
        r.set(right - w, cy - h / 2, right, cy + h / 2)
        bg.color = theme.accentSoft
        c.drawRoundRect(r, h / 2, h / 2, bg)
        tagPaint.color = theme.accent; tagPaint.textAlign = Paint.Align.CENTER; tagPaint.typeface = Ui.regular
        val fm = tagPaint.fontMetrics
        c.drawText(s, r.centerX(), cy - (fm.ascent + fm.descent) / 2, tagPaint)
    }

    /** [label] shortened with … until its chip fits in [maxW]. */
    private fun fit(label: String, maxW: Float, textSp: Float): String {
        if (chipWidth(label, chevron = true, textSp = textSp) <= maxW) return label
        var n = label.length
        while (n > 1 && chipWidth(label.take(n) + "…", chevron = true, textSp = textSp) > maxW) n--
        return label.take(n).trimEnd('·', ' ') + "…"
    }

    private fun layout(s: String, w: Int, color: Int, cut: TextUtils.TruncateAt, lines: Int, medium: Boolean = false, marked: Boolean = false): StaticLayout {
        body.color = color; body.typeface = if (medium) Ui.medium else Ui.regular
        val text: CharSequence = if (marked) marks?.invoke(s) ?: s else s
        return StaticLayout.Builder.obtain(text, 0, text.length, body, w).setMaxLines(lines).setEllipsize(cut)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).build()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // the row of choices: slid sideways with the finger, a tap picks
        if ((picking == "mode" || picking == "opt") && (chipDown || (e.actionMasked == MotionEvent.ACTION_DOWN && chipRow.contains(e.x, e.y)))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { chipDown = true; chipDrag = false; chipX = e.x; pressed = hits.at(e.x, e.y); onFeedback?.invoke() }
                MotionEvent.ACTION_MOVE -> {
                    if (!chipDrag && kotlin.math.abs(e.x - chipX) > dp(8f)) { chipDrag = true; pressed = null }
                    if (chipDrag) { chipScroll = (chipScroll - (e.x - chipX)).coerceIn(0f, chipMax); chipX = e.x }
                }
                MotionEvent.ACTION_UP -> {
                    val p = pressed
                    if (!chipDrag && p != null && p.startsWith("pick:")) { val w = picking!!; picking = null; onPick?.invoke(w, p.removePrefix("pick:")) }
                    chipDown = false; pressed = null; refresh()
                }
                MotionEvent.ACTION_CANCEL -> { chipDown = false; pressed = null }
            }
            invalidate()
            return true
        }
        val list = if (picking == "engine") pick else null
        if (list != null && (list.busy || (e.actionMasked == MotionEvent.ACTION_DOWN && list.contains(e.x, e.y)))) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFeedback?.invoke()
            list.onTouch(e)?.let { id -> val w = picking!!; picking = null; onPick?.invoke(w, id); refresh() }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (val p = pressed) {
                    null -> if (picking != null) picking = null
                    "mode", "opt" -> { picking = if (picking == p) null else p; if (picking != null) revealChoice() }
                    "swap" -> { picking = null; onSwap?.invoke() }
                    "engine" -> { picking = if (picking == "engine") null else "engine"; if (picking != null) pick.reveal(); onEngine?.invoke() }
                    "close" -> onClose?.invoke()
                    "insert" -> onInsert?.invoke()
                    "clear" -> onClear?.invoke()
                    "src" -> srcLayout?.let { l ->
                        // the caret goes where the text was tapped
                        val line = l.getLineForVertical((e.y - srcOrigin.y).toInt())
                        onCaret?.invoke(l.getOffsetForHorizontal(line, e.x - srcOrigin.x).coerceIn(0, source.length))
                    }
                    else -> if (p.startsWith("b:")) onButton?.invoke(p.removePrefix("b:"))
                }
                pressed = null; refresh()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private var chipDown = false
    private var chipDrag = false
    private var chipX = 0f

    /** The row opens with the current choice in view. */
    private fun revealChoice() {
        val list = if (picking == "mode") modes else opts
        var x = 0f
        for (item in list.items) {
            val w = chipWidth(item.name, textSp = Ui.SMALL + 1f)
            if (item.id == list.current || item.sub == "当前") { chipScroll = (x - dp(40f)).coerceAtLeast(0f); return }
            x += w + dp(6f)
        }
        chipScroll = 0f
    }

    /** The list a test or the host opens by hand. */
    fun open(list: String?) { picking = list; refresh() }

    private companion object { const val MAX_LINES = 6 }
}
