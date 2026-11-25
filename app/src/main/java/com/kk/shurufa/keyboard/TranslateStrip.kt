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
 * 小K's strip above the toolbar, for 改写 · 翻译 · 聊天 alike: one chip at the top left says what
 * it does now (改写 · 纠错, 翻译 · 自动 → English, 聊天) and opens the list of what it can do; the
 * engine and ✕ on the right. Below, the text it works on, typed with the keyboard (or brought in
 * with 一键插入), and under it the answer with its buttons.
 *
 * It grows when it needs to (the list open, a long answer) and shrinks back, smoothly; a change of
 * what it does crossfades the content.
 */
@SuppressLint("ViewConstructor")
class TranslateStrip(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onPick: ((which: String, id: String) -> Unit)? = null
    var onSwap: (() -> Unit)? = null
    var onEngine: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onButton: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null
    /** 一键插入: fills the empty box with [insertable] (what was copied, or what is in the field). */
    var onInsert: (() -> Unit)? = null
    var insertable = ""

    /** "translate", "rewrite" or "chat". */
    var mode = "translate"
    /** The chip at the top left: what the strip does now. */
    var modeLabel = "翻译"
    var showSwap = true
    var placeholder = "输入要翻译的文字"
    /** A small tag at the end of the text line (改写 by instruction: what it will change). */
    var sourceTag = ""
    var resultHint = "译文会显示在这里"
    var busyText = "翻译中…"
    /** The answer's buttons, (id, label); the last is the main one. */
    var buttons: List<Pair<String, String>> = emptyList()

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
    /** What the top-left chip offers. */
    var menuItems: List<PickList.Item>
        get() = menu.items
        set(v) { menu.items = v }
    var menuCurrent: String
        get() = menu.current
        set(v) { menu.current = v }
    private val pick = PickList(this).apply { rowDp = 36f }
    private val menu = PickList(this).apply { rowDp = 38f }
    private var picking: String? = null   // "menu" | "engine"

    /** The texts changed: lay them out again. */
    fun refresh() { srcLayout = null; resLayout = null; caretSince = SystemClock.uptimeMillis(); if (isLaidOut && heightT.target != needFor(width)) requestLayout(); invalidate() }

    /** What it does changed: the content crossfades into the new one. */
    fun switched() {
        picking = null
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // the height its content needs, worked out before it is drawn, and moved toward smoothly
        val h = needFor(w)
        if (heightT.value < 0f || !isLaidOut || !Motion.on(prefs)) heightT.set(h)
        else if (heightT.target != h) heightT.animateTo(h, dur(260), Motion.ENTER)
        setMeasuredDimension(w, heightT.value.toInt())
    }

    /** Asks for a new height if the content needs one. */
    private fun wantHeight(h: Float) { if (heightT.target != h) requestLayout() }

    private fun needFor(w: Int): Float {
        when (picking) { "engine" -> return dp(250f); "menu" -> return dp(270f) }
        if (mode == "translate") return baseH
        body.textSize = sp(Ui.BODY)
        val textW = (w - 2 * dp(Ui.EDGE) - dp(28f)).toInt().coerceAtLeast(1)
        val res = when { result.isNotEmpty() -> result; busy -> busyText; status.isNotEmpty() -> status; else -> resultHint }
        val lines = StaticLayout.Builder.obtain(res, 0, res.length, body, textW).setMaxLines(MAX_LINES).setLineSpacing(0f, 1.05f).build().lineCount
        val withButtons = buttons.isNotEmpty()
        val need = dp(6f) + dp(8f) + dp(30f) + dp(4f) + dp(48f) + dp(1f) + dp(8f) + lines.coerceAtLeast(1) * body.fontSpacing * 1.05f +
            (if (withButtons) dp(8f) + dp(Ui.CHIP_H) else 0f) + dp(16f)
        return maxOf(baseH, need)
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
        // header: what it does … engine ×. The right end is placed first; the chip on the left
        // gets what is left, shortened if it must be
        val hy = cardT + dp(8f); val hh = dp(30f)
        r.set(cardR - dp(6f) - hh - dp(6f), hy, cardR - dp(6f), hy + hh)
        drawChip(c, r, "", ChipStyle.GHOST, pressed = pressed == "close", icon = "close")
        hits.add("close", r, slop = dp(4f))
        val engineLabel = fit(engineName, (cardR - cardL) * 0.3f, Ui.SMALL)
        val ew = chipWidth(engineLabel, chevron = true, textSp = Ui.SMALL)
        r.set(r.left - dp(4f) - ew, hy + dp(2f), r.left - dp(4f), hy + hh - dp(2f))
        drawChip(c, r, engineLabel, if (picking == "engine") ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "engine", chevron = true, textSp = Ui.SMALL)
        hits.add("engine", r, slop = dp(4f))
        val engineLeft = r.left
        val swapW = if (showSwap) hh + dp(8f) else 0f
        val room = engineLeft - dp(10f) - (cardL + dp(8f)) - swapW
        val label = fit(modeLabel, room, Ui.SMALL + 1f)
        val mw = chipWidth(label, chevron = true, textSp = Ui.SMALL + 1f)
        r.set(cardL + dp(8f), hy, cardL + dp(8f) + mw, hy + hh)
        drawChip(c, r, label, ChipStyle.ACTIVE, pressed = pressed == "menu" || picking == "menu", chevron = true, textSp = Ui.SMALL + 1f)
        hits.add("menu", r, slop = dp(4f))
        if (showSwap) {
            r.set(r.right + dp(2f), hy, r.right + dp(2f) + hh + dp(6f), hy + hh)
            drawChip(c, r, "", ChipStyle.GHOST, pressed = pressed == "swap", icon = "swap")
            hits.add("swap", r, slop = dp(4f))
        }
        val innerL = cardL + dp(14f); val innerR = cardR - dp(14f)
        val innerT = hy + hh + dp(4f); val innerB = cardB - dp(8f)
        val listArea = r.apply { set(cardL + dp(4f), hy + hh + dp(2f), cardR - dp(4f), cardB - dp(4f)) }
        when (picking) {
            "engine" -> { if (pick.draw(c, listArea, t, rad)) postInvalidateOnAnimation(); return }
            "menu" -> { if (menu.draw(c, listArea, t, rad)) postInvalidateOnAnimation(); return }
        }
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
        val compact = mode == "translate"
        body.textSize = sp(Ui.BODY)
        // the text line: two lines at most, its end in view
        val inputH = dp(48f)
        val tagW = when {
            source.isEmpty() && !sourceIsSelection && insertable.isNotEmpty() -> chipWidth("一键插入", icon = "clipboard", textSp = Ui.SMALL + 1f)
            sourceTag.isNotEmpty() -> tagWidth(sourceTag)
            else -> 0f
        }
        val srcW = (innerR - innerL - (if (tagW > 0f) tagW + dp(8f) else 0f) - dp(6f)).toInt().coerceAtLeast(1)
        val src = source.ifEmpty { placeholder }
        val sl = srcLayout ?: layout(src, srcW, if (source.isEmpty()) t.hint else t.inkSoft, TextUtils.TruncateAt.START, 2).also { srcLayout = it }
        val srcTop = innerT + (inputH - sl.height) / 2
        // the placeholder sits just after the caret, not under it
        val srcX = innerL + if (source.isEmpty() && !sourceIsSelection) dp(5f) else 0f
        c.save(); c.translate(srcX, srcTop); sl.draw(c); c.restore()
        if (!sourceIsSelection) {
            // a caret after the typed text, blinking at half a second
            val since = SystemClock.uptimeMillis() - caretSince
            val on = (since / 530) % 2 == 0L
            postInvalidateDelayed(530 - since % 530)
            if (on) {
                val line = sl.lineCount - 1
                val cx = innerL + (if (source.isEmpty()) 0f else sl.getLineRight(line) + dp(1.5f))
                val top = srcTop + sl.getLineTop(line)
                bg.color = t.accent
                c.drawRect(cx, top + dp(2f), cx + dp(1.6f), srcTop + sl.getLineBottom(line) - dp(2f), bg)
            }
        }
        if (source.isEmpty() && !sourceIsSelection && insertable.isNotEmpty()) {
            // 一键插入: the box is empty, and there is something to work on at hand
            r.set(innerR - tagW, innerT + (inputH - btnH) / 2, innerR, innerT + (inputH + btnH) / 2)
            drawChip(c, r, "一键插入", ChipStyle.SOFT, pressed = pressed == "insert", icon = "clipboard", textSp = Ui.SMALL + 1f)
            hits.add("insert", r, slop = dp(6f))
        } else if (sourceTag.isNotEmpty()) drawTag(c, sourceTag, innerR, innerT + inputH / 2)
        bg.color = t.divider
        c.drawRect(innerL, innerT + inputH, innerR, innerT + inputH + dp(0.8f), bg)
        // the answer
        val resT = innerT + inputH + dp(1f)
        val res = when { result.isNotEmpty() -> result; busy -> busyText; status.isNotEmpty() -> status; else -> resultHint }
        val withButtons = buttons.isNotEmpty() && (result.isNotEmpty() || !compact)
        val bw = buttons.sumOf { (_, l) -> chipWidth(l, textSp = Ui.SMALL + 1f).toDouble() }.toFloat() + dp(6f) * (buttons.size - 1).coerceAtLeast(0)
        if (compact) {
            // 翻译 as it was: the answer beside its one button
            val textW = (innerR - innerL - (if (withButtons) bw + dp(10f) else 0f)).toInt().coerceAtLeast(1)
            val rl = resLayout ?: layout(res, textW, if (result.isNotEmpty()) t.ink else t.hint, TextUtils.TruncateAt.END, 2, medium = result.isNotEmpty()).also { resLayout = it }
            val half = innerB - resT
            c.save(); c.translate(innerL, resT + (half - rl.height) / 2); rl.draw(c); c.restore()
            if (withButtons) drawButtons(c, innerR, resT + (half - btnH) / 2, btnH)
            return
        }
        // 改写 · 聊天: the answer in full width (as many lines as it needs, up to a point), buttons under it
        val textW = (innerR - innerL).toInt().coerceAtLeast(1)
        val rl = resLayout ?: layout(res, textW, if (result.isNotEmpty()) t.ink else t.hint, TextUtils.TruncateAt.END, MAX_LINES, medium = false, marked = result.isNotEmpty()).also { resLayout = it }
        val textTop = resT + dp(8f)
        c.save(); c.clipRect(innerL, resT, innerR, innerB - (if (withButtons) btnH + dp(6f) else 0f)); c.translate(innerL, textTop); rl.draw(c); c.restore()
        if (withButtons) drawButtons(c, innerR, innerB - btnH, btnH)
        wantHeight(needFor(width))
    }

    /** Spans set on [result] by the host (the changes of 改写, marked). */
    var marks: ((CharSequence) -> CharSequence)? = null

    private fun drawButtons(c: Canvas, right: Float, top: Float, h: Float) {
        var x = right
        for ((i, b) in buttons.withIndex().reversed()) {
            val (id, label) = b
            val w = chipWidth(label, textSp = Ui.SMALL + 1f)
            r.set(x - w, top, x, top + h)
            drawChip(c, r, label, if (i == buttons.size - 1) ChipStyle.PRIMARY else ChipStyle.SOFT, pressed = pressed == "b:$id", textSp = Ui.SMALL + 1f)
            hits.add("b:$id", r, slop = dp(5f))
            x = r.left - dp(6f)
        }
    }

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
        val list = when (picking) { "engine" -> pick; "menu" -> menu; else -> null }
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
                    "menu" -> { picking = if (picking == "menu") null else "menu"; if (picking != null) menu.reveal() }
                    "swap" -> { picking = null; onSwap?.invoke() }
                    "engine" -> { picking = if (picking == "engine") null else "engine"; if (picking != null) pick.reveal(); onEngine?.invoke() }
                    "close" -> onClose?.invoke()
                    "insert" -> onInsert?.invoke()
                    else -> if (p.startsWith("b:")) onButton?.invoke(p.removePrefix("b:"))
                }
                pressed = null; refresh()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private companion object { const val MAX_LINES = 7 }
}
