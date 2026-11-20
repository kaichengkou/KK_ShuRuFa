package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs

/**
 * 翻译, opened above the toolbar: the language pair (tap either side to pick, ⇄ to swap), the
 * engine, the text being translated (typed with the keyboard below, or the selection), and the
 * translation with 上屏 / 替换.
 */
@SuppressLint("ViewConstructor")
class TranslateStrip(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onPick: ((which: String, lang: String) -> Unit)? = null
    var onSwap: (() -> Unit)? = null
    var onEngine: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

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
    private var picking: String? = null   // "from" | "to"

    fun refresh() { srcLayout = null; resLayout = null; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var srcLayout: StaticLayout? = null
    private var resLayout: StaticLayout? = null
    private val hits = mutableListOf<Pair<RectF, String>>()
    private var pressed: String? = null
    private val caret = Tween(1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(132f).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val pad = dp(10f)
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        val card = RectF(pad, dp(6f), width - pad, height - dp(4f))
        bg.color = theme.surface
        c.drawRoundRect(card, rad, rad, bg)
        // header: from ⇄ to · engine · close
        val hy = card.top + dp(6f); val hh = dp(30f)
        tp.textSize = sp(Ui.SMALL)
        var x = card.left + dp(8f)
        x = pill(c, "from", fromName + " ▾", x, hy, hh, picking == "from") + dp(2f)
        val sw = RectF(x, hy, x + hh, hy + hh)
        if (pressed == "swap") { bg.color = theme.soft; c.drawRoundRect(sw, hh / 2, hh / 2, bg) }
        tp.color = theme.inkSoft; text(c, "⇄", sw.centerX(), sw.centerY())
        hits += sw to "swap"
        x = sw.right + dp(2f)
        pill(c, "to", toName + " ▾", x, hy, hh, picking == "to")
        val cl = RectF(card.right - dp(8f) - hh, hy, card.right - dp(8f), hy + hh)
        if (pressed == "close") { bg.color = theme.soft; c.drawRoundRect(cl, hh / 2, hh / 2, bg) }
        ip.color = theme.inkSoft; Icons.draw(c, "close", cl.centerX(), cl.centerY(), dp(15f), ip)
        hits += cl to "close"
        tp.textSize = sp(Ui.CAPTION + 0.5f)
        val ew = tp.measureText(engineName) + dp(18f)
        val er = RectF(cl.left - dp(4f) - ew, hy + dp(4f), cl.left - dp(4f), hy + hh - dp(4f))
        bg.color = if (pressed == "engine") theme.softPressed else theme.soft
        c.drawRoundRect(er, er.height() / 2, er.height() / 2, bg)
        tp.color = theme.inkSoft; text(c, engineName, er.centerX(), er.centerY())
        hits += er to "engine"
        val inner = RectF(card.left + dp(12f), hy + hh + dp(4f), card.right - dp(12f), card.bottom - dp(8f))
        if (picking != null) { drawPicker(c, inner); return }
        // the text and its translation
        val btnW = dp(58f); val btnH = dp(32f)
        val textW = (inner.width() - btnW - dp(10f)).toInt().coerceAtLeast(1)
        val half = inner.height() / 2
        body.textSize = sp(Ui.BODY)
        val src = source.ifEmpty { "输入要翻译的文字" }
        val sl = srcLayout ?: layout(src, textW, if (source.isEmpty()) theme.hint else theme.inkSoft, 2, TextUtils.TruncateAt.START).also { srcLayout = it }
        c.save(); c.translate(inner.left, inner.top + (half - sl.height) / 2); sl.draw(c); c.restore()
        if (!sourceIsSelection && source.isNotEmpty()) {
            // a caret after the typed text
            if (!caret.running) { caret.set(0f); caret.animateTo(1f, 1000, android.view.animation.LinearInterpolator()) }
            postInvalidateDelayed(120)
            if (caret.value < 0.55f) {
                val line = sl.lineCount - 1
                val cx = inner.left + sl.getLineRight(line) + dp(1.5f)
                val top = inner.top + (half - sl.height) / 2 + sl.getLineTop(line)
                bg.color = theme.accent
                c.drawRect(cx, top + dp(2f), cx + dp(1.6f), top + sl.getLineBottom(line) - sl.getLineTop(line) - dp(2f), bg)
            }
        }
        bg.color = theme.divider
        c.drawRect(inner.left, inner.top + half, inner.right, inner.top + half + dp(0.8f), bg)
        val res = when { result.isNotEmpty() -> result; busy -> "翻译中…"; else -> status }
        val rl = resLayout ?: layout(res, textW, if (result.isNotEmpty()) theme.ink else theme.hint, 2, TextUtils.TruncateAt.END, medium = result.isNotEmpty()).also { resLayout = it }
        c.save(); c.translate(inner.left, inner.top + half + (half - rl.height) / 2); rl.draw(c); c.restore()
        if (result.isNotEmpty()) {
            val b = RectF(inner.right - btnW, inner.top + half + (half - btnH) / 2, inner.right, inner.top + half + (half + btnH) / 2)
            bg.color = if (pressed == "commit") theme.accentPressed else theme.accent
            c.drawRoundRect(b, btnH / 2, btnH / 2, bg)
            tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium; tp.color = theme.onAccent
            text(c, if (sourceIsSelection) "替换" else "上屏", b.centerX(), b.centerY())
            tp.typeface = Ui.regular
            hits += b to "commit"
        }
    }

    private fun drawPicker(c: Canvas, area: RectF) {
        val list = if (picking == "from") listOf("auto" to "自动识别") + languages else languages
        val cur = if (picking == "from") from else to
        tp.textSize = sp(Ui.SMALL)
        val h = dp(30f); val gap = dp(6f)
        var x = area.left; var y = area.top + dp(2f)
        for ((id, name) in list) {
            val w = tp.measureText(name) + dp(24f)
            if (x + w > area.right) { x = area.left; y += h + gap }
            if (y + h > area.bottom + dp(4f)) break
            val r = RectF(x, y, x + w, y + h)
            bg.color = if (id == cur) theme.accentSoft else if (pressed == "lang:$id") theme.softPressed else theme.soft
            c.drawRoundRect(r, h / 2, h / 2, bg)
            tp.color = if (id == cur) theme.accent else theme.ink
            tp.typeface = if (id == cur) Ui.medium else Ui.regular
            text(c, name, r.centerX(), r.centerY())
            hits += r to "lang:$id"
            x += w + gap
        }
        tp.typeface = Ui.regular
    }

    private fun pill(c: Canvas, id: String, label: String, x: Float, y: Float, h: Float, on: Boolean): Float {
        tp.textSize = sp(Ui.SMALL)
        val w = tp.measureText(label) + dp(22f)
        val r = RectF(x, y, x + w, y + h)
        bg.color = if (on) theme.accentSoft else if (pressed == id) theme.softPressed else theme.soft
        c.drawRoundRect(r, h / 2, h / 2, bg)
        tp.color = if (on) theme.accent else theme.ink; tp.typeface = Ui.medium
        text(c, label, r.centerX(), r.centerY())
        tp.typeface = Ui.regular
        hits += r to id
        return r.right
    }

    private fun layout(s: String, w: Int, color: Int, lines: Int, cut: TextUtils.TruncateAt, medium: Boolean = false): StaticLayout {
        body.color = color; body.typeface = if (medium) Ui.medium else Ui.regular
        return StaticLayout.Builder.obtain(s, 0, s.length, body, w).setMaxLines(lines).setEllipsize(cut)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).build()
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second
                if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (val p = pressed) {
                    null -> {}
                    "from", "to" -> picking = if (picking == p) null else p
                    "swap" -> { picking = null; onSwap?.invoke() }
                    "engine" -> onEngine?.invoke()
                    "close" -> onClose?.invoke()
                    "commit" -> onCommit?.invoke()
                    else -> if (p.startsWith("lang:")) { val w = picking ?: "to"; picking = null; onPick?.invoke(w, p.removePrefix("lang:")) }
                }
                pressed = null; refresh()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
