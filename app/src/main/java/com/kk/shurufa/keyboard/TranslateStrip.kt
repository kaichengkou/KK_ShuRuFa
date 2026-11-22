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
 * 翻译, opened above the toolbar: the language pair (tap either side to pick, swap between them),
 * the engine, the text being translated (typed with the keyboard below, or the selection), and
 * the translation with 上屏 / 替换.
 */
@SuppressLint("ViewConstructor")
class TranslateStrip(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var onPick: ((which: String, lang: String) -> Unit)? = null
    var onSwap: (() -> Unit)? = null
    var onEngine: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onFeedback: (() -> Unit)? = null
    /** 一键插入: fills the empty box with [insertable] (what was copied, or what is in the field). */
    var onInsert: (() -> Unit)? = null
    var insertable = ""
    /** 上屏 replaces the text it came from (a selection, or what 一键插入 took from the field). */
    var replaces = false

    var from = "auto"; var to = "en"
    var fromName = "自动"; var toName = "English"
    var engineName = "离线"
    var source = ""
    var sourceIsSelection = false
    var result = ""
    var status = ""
    var busy = false
    /** Languages offered by the picker: (id, name). */
    var languages: List<Pair<String, String>> = emptyList()
    /** Engines offered by the engine chip: (engine, name); the current one is [engine]. */
    var engines: List<PickList.Item>
        get() = pick.items
        set(v) { pick.items = v }
    var engine: String
        get() = pick.current
        set(v) { pick.current = v }
    private val pick = PickList(this).apply { rowDp = 36f }
    private var picking: String? = null   // "from" | "to" | "engine"

    /** The texts changed: lay them out again. */
    fun refresh() { srcLayout = null; resLayout = null; caretSince = SystemClock.uptimeMillis(); invalidate() }

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

    override fun onThemeChanged() { srcLayout = null; resLayout = null }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(136f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        val pad = dp(Ui.EDGE)
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        val card = area.apply { set(pad, dp(6f), width - pad, height - dp(4f)) }
        bg.color = t.surface
        c.drawRoundRect(card, rad, rad, bg)
        val cardL = card.left; val cardR = card.right; val cardT = card.top; val cardB = card.bottom
        // header: from ⇄ to … engine ×. The right end is placed first; the two languages share what
        // is left, their names shortened if they must be, so nothing ever overlaps
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
        var x = cardL + dp(8f)
        val swapW = hh + dp(10f)
        val room = engineLeft - dp(8f) - x - swapW
        val fromW = chipWidth(fromName, chevron = true, textSp = Ui.SMALL + 1f)
        val toW = chipWidth(toName, chevron = true, textSp = Ui.SMALL + 1f)
        // the shorter keeps its name; the longer gets the rest
        val (fromMax, toMax) = if (fromW + toW <= room) fromW to toW
            else if (fromW <= room / 2) fromW to room - fromW
            else if (toW <= room / 2) room - toW to toW
            else room / 2 to room / 2
        x = chip(c, "from", fit(fromName, fromMax, Ui.SMALL + 1f), x, hy, hh, if (picking == "from") ChipStyle.ACTIVE else ChipStyle.SOFT, chevron = true)
        r.set(x + dp(2f), hy, x + dp(2f) + hh + dp(6f), hy + hh)
        drawChip(c, r, "", ChipStyle.GHOST, pressed = pressed == "swap", icon = "swap")
        hits.add("swap", r, slop = dp(4f))
        x = r.right + dp(2f)
        chip(c, "to", fit(toName, toMax, Ui.SMALL + 1f), x, hy, hh, if (picking == "to") ChipStyle.ACTIVE else ChipStyle.SOFT, chevron = true)
        val innerL = cardL + dp(14f); val innerR = cardR - dp(14f)
        val innerT = hy + hh + dp(4f); val innerB = cardB - dp(8f)
        if (picking == "engine") {
            if (pick.draw(c, r.apply { set(cardL + dp(4f), hy + hh + dp(2f), cardR - dp(4f), cardB - dp(4f)) }, t, rad)) postInvalidateOnAnimation()
            return
        }
        if (picking != null) { drawPicker(c, innerL, innerT, innerR, innerB); return }
        // the text and its translation
        val btnW = chipWidth("替换", textSp = Ui.SMALL + 1f); val btnH = dp(Ui.CHIP_H)
        val textW = (innerR - innerL - btnW - dp(10f)).toInt().coerceAtLeast(1)
        val half = (innerB - innerT) / 2
        body.textSize = sp(Ui.BODY)
        val src = source.ifEmpty { "输入要翻译的文字" }
        val sl = srcLayout ?: layout(src, textW, if (source.isEmpty()) t.hint else t.inkSoft, TextUtils.TruncateAt.START).also { srcLayout = it }
        val srcTop = innerT + (half - sl.height) / 2
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
        bg.color = t.divider
        c.drawRect(innerL, innerT + half, innerR, innerT + half + dp(0.8f), bg)
        val res = when { result.isNotEmpty() -> result; busy -> "翻译中…"; status.isNotEmpty() -> status; else -> "译文会显示在这里" }
        val rl = resLayout ?: layout(res, textW, if (result.isNotEmpty()) t.ink else t.hint, TextUtils.TruncateAt.END, medium = result.isNotEmpty()).also { resLayout = it }
        c.save(); c.translate(innerL, innerT + half + (half - rl.height) / 2); rl.draw(c); c.restore()
        if (source.isEmpty() && !sourceIsSelection && insertable.isNotEmpty()) {
            // 一键插入: the box is empty, and there is something to translate at hand
            val iw = chipWidth("一键插入", icon = "clipboard", textSp = Ui.SMALL + 1f)
            r.set(innerR - iw, innerT + (half - btnH) / 2, innerR, innerT + (half + btnH) / 2)
            drawChip(c, r, "一键插入", ChipStyle.SOFT, pressed = pressed == "insert", icon = "clipboard", textSp = Ui.SMALL + 1f)
            hits.add("insert", r, slop = dp(6f))
        }
        if (result.isNotEmpty()) {
            r.set(innerR - btnW, innerT + half + (half - btnH) / 2, innerR, innerT + half + (half + btnH) / 2)
            drawChip(c, r, if (replaces) "替换" else "上屏", ChipStyle.PRIMARY, pressed = pressed == "commit", textSp = Ui.SMALL + 1f)
            hits.add("commit", r, slop = dp(6f))
        }
    }

    private fun drawPicker(c: Canvas, l: Float, top: Float, right: Float, bottom: Float) {
        val list = when (picking) { "from" -> listOf("auto" to "自动识别") + languages; else -> languages }
        val cur = when (picking) { "from" -> from; else -> to }
        val h = dp(30f); val gap = dp(6f)
        var x = l; var y = top + dp(2f)
        for ((id, name) in list) {
            val w = chipWidth(name, textSp = Ui.SMALL + 1f)
            if (x + w > right) { x = l; y += h + gap }
            if (y + h > bottom + dp(4f)) break
            r.set(x, y, x + w, y + h)
            drawChip(c, r, name, if (id == cur) ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "lang:$id", textSp = Ui.SMALL + 1f)
            hits.add("lang:$id", r)
            x += w + gap
        }
    }

    /** A header chip at [x]; returns its right edge. */
    /** [label] shortened with … until its chip fits in [maxW]. */
    private fun fit(label: String, maxW: Float, textSp: Float): String {
        if (chipWidth(label, chevron = true, textSp = textSp) <= maxW) return label
        var n = label.length
        while (n > 1 && chipWidth(label.take(n) + "…", chevron = true, textSp = textSp) > maxW) n--
        return label.take(n).trimEnd('·', ' ') + "…"
    }

    private fun chip(c: Canvas, id: String, label: String, x: Float, y: Float, h: Float, style: ChipStyle, chevron: Boolean): Float {
        val w = chipWidth(label, chevron = chevron, textSp = Ui.SMALL + 1f)
        r.set(x, y, x + w, y + h)
        drawChip(c, r, label, style, pressed = pressed == id, chevron = chevron, textSp = Ui.SMALL + 1f)
        hits.add(id, r, slop = dp(4f))
        return r.right
    }

    private fun layout(s: String, w: Int, color: Int, cut: TextUtils.TruncateAt, medium: Boolean = false): StaticLayout {
        body.color = color; body.typeface = if (medium) Ui.medium else Ui.regular
        return StaticLayout.Builder.obtain(s, 0, s.length, body, w).setMaxLines(2).setEllipsize(cut)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).build()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (picking == "engine" && (pick.busy || (e.actionMasked == MotionEvent.ACTION_DOWN && pick.contains(e.x, e.y)))) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFeedback?.invoke()
            pick.onTouch(e)?.let { id -> picking = null; onPick?.invoke("engine", id) }
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
                    null -> {}
                    "from", "to" -> picking = if (picking == p) null else p
                    "swap" -> { picking = null; onSwap?.invoke() }
                    "engine" -> { picking = if (picking == "engine") null else "engine"; if (picking != null) pick.reveal(); onEngine?.invoke() }
                    "close" -> onClose?.invoke()
                    "commit" -> onCommit?.invoke()
                    "insert" -> onInsert?.invoke()
                    else -> if (p.startsWith("lang:")) { val w = picking ?: "to"; picking = null; onPick?.invoke(w, p.removePrefix("lang:")) }
                }
                pressed = null; refresh()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
