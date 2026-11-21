package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs

/**
 * AI 改写. What will be sent and the service it goes to on top; the actions in groups
 * (改写 · 翻译 · 回复); and a result card that fills in while the answer streams, with the
 * changed characters marked for 润色 and 纠错, and 重试 · 复制 · 插入 · 替换.
 */
@SuppressLint("ViewConstructor")
class AiPanel(context: Context, private val prefs: Prefs) : View(context) {
    class Act(val id: String, val title: String, val group: String)

    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var actions: List<Act> = emptyList()
        set(v) { field = v; invalidate() }
    var onAction: ((String) -> Unit)? = null
    var onButton: ((String) -> Unit)? = null     // "replace" "insert" "copy" "retry" "setup" "custom" "target:<id>"
    var onFeedback: (() -> Unit)? = null

    var source = ""
    var sourceLabel = ""
    var provider = ""
    /** Services and models to pick from (header chip): (target, label). */
    var targets: List<PickList.Item>
        get() = pick.items
        set(v) { pick.items = v }
    var currentTarget: String
        get() = pick.current
        set(v) { pick.current = v }
    private val pick = PickList(this).apply { title = "改写用的模型 · 在设置里启用更多" }
    private var picking = false
    var needsSetup = false
    var running: String? = null
    var doneTitle = ""
    var status = ""
    private var result = ""
    private var diffBase: String? = null

    /** The answer so far; [base] marks what changed against the original (润色/纠错). */
    fun setResult(text: String, base: String?) { result = text; diffBase = base; resLayout = null; invalidate() }
    fun refresh() { resLayout = null; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var resLayout: StaticLayout? = null
    private val hits = mutableListOf<Pair<RectF, String>>()
    private var pressed: String? = null
    private val pulse = Tween(0f)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        hits.clear()
        val pad = dp(10f)
        val rad = dp(prefs.keyRadiusDp)
        var y = dp(8f)
        // header: source preview, provider chip
        tp.textSize = sp(Ui.CAPTION + 0.5f)
        val pw = tp.measureText("$provider ▾") + dp(22f)
        val pr = RectF(width - pad - pw, y, width - pad, y + dp(24f))
        if (!needsSetup) {
            bg.color = if (pressed == "provider") theme.softPressed else theme.soft
            c.drawRoundRect(pr, pr.height() / 2, pr.height() / 2, bg)
            tp.textAlign = Paint.Align.CENTER; tp.color = theme.inkSoft
            text(c, "$provider ▾", pr.centerX(), pr.centerY())
            hits += pr to "provider"
        }
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium; tp.color = theme.hint
        c.drawText(sourceLabel, pad + dp(2f), pr.centerY() + tp.textSize * 0.36f, tp)
        val lx = pad + dp(2f) + tp.measureText(sourceLabel) + dp(8f)
        tp.typeface = Ui.regular; tp.color = theme.inkSoft
        val avail = pr.left - dp(8f) - lx
        c.drawText(TextUtils.ellipsize(source.replace('\n', ' '), TextPaint(tp), avail.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString(), lx, pr.centerY() + tp.textSize * 0.36f, tp)
        y = pr.bottom + dp(8f)
        if (needsSetup) {
            val r = RectF(pad, y, width - pad, y + dp(48f))
            bg.color = if (pressed == "setup") theme.accentPressed else theme.accent
            c.drawRoundRect(r, rad, rad, bg)
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY); tp.typeface = Ui.medium; tp.color = theme.onAccent
            text(c, "设置一个 AI 服务（Claude、OpenAI、DeepSeek、Gemini…）", r.centerX(), r.centerY())
            tp.typeface = Ui.regular
            hits += r to "setup"
            return
        }
        val ch = dp(34f); val gap = dp(6f)
        var x = pad
        if (picking) {
            // 选择模型: every enabled service and model, in a list that scrolls
            if (pick.draw(c, RectF(pad, y, width - pad, height - dp(8f)), theme, Ui.radiusLarge(rad, dp(1f)))) postInvalidateOnAnimation()
            return
        }
        // groups of chips, flowing
        if (running != null) { if (!pulse.running) { pulse.set(0f); pulse.animateTo(1f, 1000) }; postInvalidateOnAnimation() }
        var lastGroup = ""
        for (a in actions) {
            tp.textSize = sp(Ui.BODY)
            val w = tp.measureText(a.title) + dp(24f)
            var labelW = 0f
            if (a.group != lastGroup) {
                tp.textSize = sp(Ui.CAPTION)
                labelW = tp.measureText(a.group) + dp(8f)
                if (x > pad) x += dp(6f)
            }
            if (x + labelW + w > width - pad) { x = pad; y += ch + gap }
            if (a.group != lastGroup) {
                tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.CAPTION); tp.color = theme.hint
                c.drawText(a.group, x, y + ch / 2 + tp.textSize * 0.36f, tp)
                x += labelW; lastGroup = a.group
            }
            val r = RectF(x, y, x + w, y + ch)
            val on = running == a.id
            bg.color = when {
                on -> blend(theme.accentSoft, theme.accent, 0.15f + 0.2f * kotlin.math.sin(pulse.value * Math.PI).toFloat())
                pressed == a.id -> theme.softPressed
                else -> theme.soft
            }
            c.drawRoundRect(r, ch / 2, ch / 2, bg)
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY)
            tp.color = if (on) theme.accent else theme.ink
            text(c, a.title, r.centerX(), r.centerY())
            hits += r to "act:${a.id}"
            x += w + gap
        }
        // 输入指令: type your own instruction
        run {
            tp.textSize = sp(Ui.BODY)
            val label = "输入指令"
            val w = tp.measureText(label) + dp(24f)
            if (x + w > width - pad) { x = pad; y += ch + gap }
            val r = RectF(x, y, x + w, y + ch)
            bg.color = if (pressed == "custom") theme.accentPressed else theme.accent
            c.drawRoundRect(r, ch / 2, ch / 2, bg)
            tp.textAlign = Paint.Align.CENTER; tp.color = theme.onAccent; tp.typeface = Ui.medium
            text(c, label, r.centerX(), r.centerY())
            tp.typeface = Ui.regular
            hits += r to "custom"
        }
        y += ch + dp(10f)
        // the result card
        val card = RectF(pad, y, width - pad, height - dp(8f))
        if (card.height() < dp(48f)) return
        bg.color = theme.surface
        c.drawRoundRect(card, Ui.radiusLarge(rad, dp(1f)), Ui.radiusLarge(rad, dp(1f)), bg)
        val inner = dp(12f)
        if (result.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.color = theme.hint; tp.textSize = sp(Ui.SMALL)
            text(c, status.ifEmpty { if (running != null) "正在思考…" else "选一个操作；选中文字时只处理选中的部分" }, card.centerX(), card.centerY())
            return
        }
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.CAPTION); tp.color = theme.hint; tp.typeface = Ui.medium
        c.drawText(if (running != null) "$doneTitle · 生成中…" else doneTitle, card.left + inner, card.top + inner + tp.textSize * 0.8f, tp)
        tp.typeface = Ui.regular
        val btnH = dp(30f)
        val top = card.top + inner + dp(16f)
        val lay = resLayout ?: run {
            body.textSize = sp(Ui.BODY + 1f); body.color = theme.ink
            val w = (card.width() - 2 * inner).toInt().coerceAtLeast(1)
            val lines = ((card.bottom - inner - btnH - dp(6f) - top) / (body.fontSpacing * 1.1f)).toInt().coerceAtLeast(1)
            val text = marked(result, diffBase)
            StaticLayout.Builder.obtain(text, 0, text.length, body, w).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
                .also { resLayout = it }
        }
        c.save(); c.translate(card.left + inner, top); lay.draw(c); c.restore()
        if (running != null) return
        // buttons, right to left
        var bx = card.right - inner
        val by = card.bottom - inner - btnH
        for ((id, label, primary) in listOf(Triple("replace", "替换", true), Triple("insert", "插入", false), Triple("copy", "复制", false), Triple("retry", "重试", false))) {
            tp.textSize = sp(Ui.SMALL)
            val w = tp.measureText(label) + dp(26f)
            val r = RectF(bx - w, by, bx, by + btnH)
            bg.color = when {
                primary -> if (pressed == id) theme.accentPressed else theme.accent
                else -> if (pressed == id) theme.softPressed else theme.soft
            }
            c.drawRoundRect(r, btnH / 2, btnH / 2, bg)
            tp.textAlign = Paint.Align.CENTER; tp.typeface = if (primary) Ui.medium else Ui.regular
            tp.color = if (primary) theme.onAccent else theme.ink
            text(c, label, r.centerX(), r.centerY())
            tp.typeface = Ui.regular
            hits += r to id
            bx = r.left - dp(6f)
        }
    }

    /** The result with the characters that are new against [base] tinted. */
    private fun marked(s: String, base: String?): CharSequence {
        if (base == null || base.length > 800 || s.length > 800) return s
        val sp = SpannableString(s)
        val keep = lcsMask(base, s)
        var i = 0
        while (i < s.length) {
            if (keep[i]) { i++; continue }
            var j = i
            while (j < s.length && !keep[j]) j++
            sp.setSpan(BackgroundColorSpan(theme.accentSoft), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(theme.accent), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = j
        }
        return sp
    }

    /** For each character of [b]: is it part of the longest common subsequence with [a]? */
    private fun lcsMask(a: String, b: String): BooleanArray {
        val n = a.length; val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            d[i][j] = if (a[i] == b[j]) d[i + 1][j + 1] + 1 else maxOf(d[i + 1][j], d[i][j + 1])
        val keep = BooleanArray(m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { keep[j] = true; i++; j++ }
                d[i + 1][j] >= d[i][j + 1] -> i++
                else -> j++
            }
        }
        return keep
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float) {
        val fm = tp.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, tp)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (picking && (pick.busy || (e.actionMasked == MotionEvent.ACTION_DOWN && pick.contains(e.x, e.y)))) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFeedback?.invoke()
            pick.onTouch(e)?.let { t -> picking = false; onButton?.invoke("target:$t") }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.lastOrNull { it.first.contains(e.x, e.y) }?.second?.removePrefix("act:")
                if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                when {
                    p == null -> {}
                    p == "provider" -> { picking = !picking; if (picking) pick.reveal(); invalidate() }
                    picking -> picking = false
                    p in setOf("replace", "insert", "copy", "retry", "setup", "custom") -> onButton?.invoke(p)
                    running == null -> onAction?.invoke(p)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
