package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * AI 改写. What will be sent and the model it goes to on top; the actions in one row that slides
 * sideways (改写 · 翻译), with 输入指令 always at its end; and a result card that fills in while
 * the answer streams, with the changed characters marked for 润色 and 纠错, and 重试 · 复制 ·
 * 插入 · 替换 on its title line.
 */
@SuppressLint("ViewConstructor")
class AiPanel(context: Context, prefs: Prefs) : KbView(context, prefs) {
    class Act(val id: String, val title: String, val group: String)

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
        set(v) { field = v; if (v != null) pulse.set(0f); invalidate() }
    var doneTitle = ""
    var status = ""
        set(v) { field = v; invalidate() }
    private var result = ""
    private var diffBase: String? = null

    /** The answer so far; [base] marks what changed against the original (润色/纠错). */
    fun setResult(text: String, base: String?) { result = text; diffBase = base; resLayout = null; invalidate() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ellipsizer = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var resLayout: StaticLayout? = null
    private val hits = Hits()
    private var pressed: String? = null
    private val pulse = Tween(0f)
    private val r = RectF()
    /** The action row: where it is on screen, how far it is slid, how wide its content is. */
    private val row = RectF()
    private var rowScroll = 0f
    private var rowContentW = 0f
    private val scroller = OverScroller(context)
    private var fadeL: LinearGradient? = null
    private var fadeR: LinearGradient? = null

    override fun onThemeChanged() { resLayout = null; fadeL = null; fadeR = null }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { resLayout = null; fadeL = null; fadeR = null }

    private val pad get() = dp(Ui.EDGE + 2f)

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        var again = false
        if (scroller.computeScrollOffset()) { rowScroll = scroller.currX.toFloat(); again = true }
        val ch = dp(Ui.CHIP_H)
        var y = dp(10f)
        // header: what is sent (全文 / 选中), and the model chip
        val headH = dp(28f)
        val pw = chipWidth(provider, chevron = true, textSp = Ui.SMALL)
        if (!needsSetup) {
            r.set(width - pad - pw, y, width - pad, y + headH)
            drawChip(c, r, provider, if (picking) ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "provider", chevron = true, textSp = Ui.SMALL)
            hits.add("provider", r, slop = dp(6f))
        }
        val headCy = y + headH / 2
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium; tp.color = t.accent
        drawText(c, sourceLabel, pad, headCy, tp)
        val lx = pad + tp.measureText(sourceLabel) + dp(8f)
        tp.typeface = Ui.regular; tp.color = t.inkSoft
        val avail = (if (needsSetup) width - pad else width - pad - pw - dp(10f)) - lx
        ellipsizer.set(tp)
        drawText(c, TextUtils.ellipsize(source.replace('\n', ' '), ellipsizer, avail.coerceAtLeast(0f), TextUtils.TruncateAt.END), lx, headCy, tp)
        y += headH + dp(10f)
        if (needsSetup) { drawSetup(c, y); return }
        if (picking) {
            // 选择模型: every enabled service and model, in a list that scrolls
            if (pick.draw(c, r.apply { set(pad, y, width - pad, height - dp(10f)) }, t, Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)))) again = true
            if (again) postInvalidateOnAnimation()
            return
        }
        again = drawActions(c, y, ch) || again
        y += ch + dp(10f)
        drawResult(c, y)
        if (again) postInvalidateOnAnimation()
    }

    /** One row of actions that slides sideways; 输入指令 stays at its right end. True while animating. */
    private fun drawActions(c: Canvas, y: Float, ch: Float): Boolean {
        val t = theme
        val gap = dp(Ui.CHIP_GAP)
        val customW = chipWidth("输入指令", icon = "ai")
        r.set(width - pad - customW, y, width - pad, y + ch)
        drawChip(c, r, "输入指令", ChipStyle.PRIMARY, pressed = pressed == "custom", icon = "ai")
        hits.add("custom", r, slop = dp(3f))
        row.set(pad, y, r.left - gap, y + ch)
        val ps = if (running != null) {
            if (!pulse.running) { pulse.set(0f); pulse.animateTo(1f, 1100, Motion.LINEAR) }
            sin(pulse.value * Math.PI).toFloat()
        } else 0f
        c.save()
        c.clipRect(row.left - dp(2f), row.top - dp(4f), row.right, row.bottom + dp(4f))
        var x = row.left - rowScroll
        var lastGroup = ""
        for (a in actions) {
            if (a.group != lastGroup) {
                if (lastGroup.isNotEmpty()) x += dp(4f)
                tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.CAPTION); tp.color = t.hint; tp.typeface = Ui.medium
                drawText(c, a.group, x, y + ch / 2, tp)
                tp.typeface = Ui.regular
                x += tp.measureText(a.group) + dp(8f)
                lastGroup = a.group
            }
            val w = chipWidth(a.title)
            r.set(x, y, x + w, y + ch)
            if (r.right > row.left && r.left < row.right) {
                if (running == a.id) {
                    // the action at work breathes
                    bg.color = Colors.blend(t.accentSoft, t.accent, 0.12f + 0.18f * ps)
                    c.drawRoundRect(r, ch / 2, ch / 2, bg)
                    chipText.textSize = sp(Ui.BODY); chipText.typeface = Ui.medium; chipText.color = t.accent
                    chipText.textAlign = Paint.Align.CENTER
                    drawText(c, a.title, r.centerX(), r.centerY(), chipText)
                } else drawChip(c, r, a.title, ChipStyle.SOFT, pressed = pressed == a.id)
                // only the part of a chip inside the row takes taps
                hits.add(a.id, maxOf(r.left, row.left), r.top - dp(3f), minOf(r.right, row.right), r.bottom + dp(3f))
            }
            x += w + gap
        }
        rowContentW = x - gap + rowScroll - row.left
        c.restore()
        // edge fades say the row goes on
        val fw = dp(16f)
        if (rowScroll > 1f) {
            bg.shader = fadeL ?: LinearGradient(row.left, 0f, row.left + fw, 0f, t.background, Colors.alpha(t.background, 0), Shader.TileMode.CLAMP).also { fadeL = it }
            c.drawRect(row.left - dp(2f), row.top, row.left + fw, row.bottom, bg)
            bg.shader = null
        }
        if (rowScroll < maxRowScroll - 1f) {
            bg.shader = fadeR ?: LinearGradient(row.right - fw, 0f, row.right, 0f, Colors.alpha(t.background, 0), t.background, Shader.TileMode.CLAMP).also { fadeR = it }
            c.drawRect(row.right - fw, row.top, row.right, row.bottom, bg)
            bg.shader = null
        }
        return running != null
    }

    private val maxRowScroll get() = max(0f, rowContentW - row.width())

    /** Nothing set up yet: what this panel does, and the way to the settings. */
    private fun drawSetup(c: Canvas, top: Float) {
        val t = theme
        val card = r.apply { set(pad, top, width - pad, height - dp(10f)) }
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        bg.color = t.surface
        c.drawRoundRect(card, rad, rad, bg)
        val cx = card.centerX()
        val cardTop = card.top; val cardBottom = card.bottom
        chipIcon.color = t.accent
        Icons.draw(c, "ai", cx, cardTop + dp(30f), dp(26f), chipIcon)
        tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.TITLE); tp.typeface = Ui.medium; tp.color = t.ink
        drawText(c, "还没有设置 AI 服务", cx, cardTop + dp(60f), tp)
        tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.regular; tp.color = t.hint
        ellipsizer.set(tp)
        drawText(c, TextUtils.ellipsize("填入自己的 Key 就能用：Claude、OpenAI、DeepSeek、Gemini…", ellipsizer, card.width() - dp(24f), TextUtils.TruncateAt.END), cx, cardTop + dp(82f), tp)
        val w = chipWidth("去设置", icon = "settings")
        val by = minOf(cardTop + dp(100f), cardBottom - dp(12f) - dp(Ui.CHIP_H))
        r.set(cx - w / 2, by, cx + w / 2, by + dp(Ui.CHIP_H))
        drawChip(c, r, "去设置", ChipStyle.PRIMARY, pressed = pressed == "setup", icon = "settings")
        hits.add("setup", r, slop = dp(6f))
    }

    private fun drawResult(c: Canvas, top0: Float) {
        val t = theme
        val card = r.apply { set(pad, top0, width - pad, height - dp(10f)) }
        if (card.height() < dp(48f)) return
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        bg.color = t.surface
        c.drawRoundRect(card, rad, rad, bg)
        val cardL = card.left; val cardR = card.right; val cardT = card.top; val cardB = card.bottom
        val inner = dp(12f)
        if (result.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.color = t.hint; tp.textSize = sp(Ui.SMALL + 1f)
            drawText(c, status.ifEmpty { if (running != null) "正在思考…" else "选一个操作；选中文字时只处理选中的部分" }, card.centerX(), card.centerY(), tp)
            return
        }
        // title line: what was done, and the buttons for the answer
        val lineH = dp(28f)
        val lineCy = cardT + dp(8f) + lineH / 2
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.color = t.hint; tp.typeface = Ui.medium
        drawText(c, if (running != null) "$doneTitle · 生成中…" else doneTitle, cardL + inner, lineCy, tp)
        tp.typeface = Ui.regular
        if (running == null) {
            var bx = cardR - dp(8f)
            for ((id, label) in BUTTONS) {
                val primary = id == "replace"
                val w = chipWidth(label, textSp = Ui.SMALL + 1f)
                r.set(bx - w, lineCy - lineH / 2, bx, lineCy + lineH / 2)
                drawChip(c, r, label, if (primary) ChipStyle.PRIMARY else ChipStyle.SOFT, pressed = pressed == id, textSp = Ui.SMALL + 1f)
                hits.add(id, r, slop = dp(5f))
                bx = r.left - dp(6f)
            }
        }
        val top = lineCy + lineH / 2 + dp(6f)
        val lay = resLayout ?: run {
            body.textSize = sp(Ui.BODY + 1f); body.color = t.ink
            val w = (cardR - cardL - 2 * inner).toInt().coerceAtLeast(1)
            val lines = ((cardB - inner + dp(4f) - top) / (body.fontSpacing * 1.1f)).toInt().coerceAtLeast(1)
            val text = marked(result, diffBase)
            StaticLayout.Builder.obtain(text, 0, text.length, body, w).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
                .also { resLayout = it }
        }
        c.save(); c.translate(cardL + inner, top); lay.draw(c); c.restore()
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

    // ---- touch ----
    private var downX = 0f
    private var lastX = 0f
    private var inRow = false
    private var sliding = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (picking && (pick.busy || (e.actionMasked == MotionEvent.ACTION_DOWN && pick.contains(e.x, e.y)))) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFeedback?.invoke()
            pick.onTouch(e)?.let { t -> picking = false; onButton?.invoke("target:$t") }
            invalidate()
            return true
        }
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; lastX = e.x; sliding = false
                inRow = !picking && row.contains(e.x, e.y)
                pressed = hits.at(e.x, e.y)
                if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (inRow && !sliding && abs(e.x - downX) > slop && maxRowScroll > 0f) { sliding = true; pressed = null; invalidate() }
                if (sliding) {
                    var next = rowScroll - (e.x - lastX)
                    // past either end the row only gives a little
                    if (next < 0f || next > maxRowScroll) next = rowScroll - (e.x - lastX) * 0.35f
                    rowScroll = next.coerceIn(-dp(48f), maxRowScroll + dp(48f))
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                if (sliding) {
                    vt?.computeCurrentVelocity(1000)
                    val m = maxRowScroll.toInt()
                    if (rowScroll < 0f || rowScroll > m) scroller.springBack(rowScroll.toInt(), 0, 0, m, 0, 0)
                    else scroller.fling(rowScroll.toInt(), 0, -(vt?.xVelocity ?: 0f).toInt(), 0, 0, m, 0, 0, dp(24f).toInt(), 0)
                    sliding = false
                } else when {
                    p == null -> picking = false
                    p == "provider" -> { picking = !picking; if (picking) pick.reveal() }
                    picking -> picking = false
                    p in BUTTON_IDS -> onButton?.invoke(p)
                    running == null -> onAction?.invoke(p)
                }
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null; sliding = false; vt?.recycle(); vt = null
                if (rowScroll < 0f || rowScroll > maxRowScroll) scroller.springBack(rowScroll.toInt(), 0, 0, maxRowScroll.toInt(), 0, 0)
                invalidate()
            }
        }
        return true
    }

    private companion object {
        /** Result buttons, drawn right to left. */
        val BUTTONS = listOf("replace" to "替换", "insert" to "插入", "copy" to "复制", "retry" to "重试")
        val BUTTON_IDS = setOf("replace", "insert", "copy", "retry", "setup", "custom")
    }
}
