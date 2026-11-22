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
 * 小K's panel. On top, a switch between 聊天 (talking with 小K) and the kinds of action it can do
 * with the text (改写 · 翻译), and the model they go to. 聊天 shows the conversation, the newest
 * at the bottom, with 回话 (type to it) and 插入 (its last answer into the field).
 *
 * For the actions:
 * below, every action of the kind picked, wrapped onto as many lines as they need and ending with
 * 输入指令, so nothing has to be slid into view. Once an answer comes the actions fold away and the
 * answer gets the panel: it fills in while it streams (following its last line), the changed
 * characters are marked for 润色 and 纠错, a long one scrolls, and 重试 · 复制 · 插入 · 替换 sit
 * on its title line; ✕ brings the actions back.
 */
@SuppressLint("ViewConstructor")
class AiPanel(context: Context, prefs: Prefs) : KbView(context, prefs) {
    class Act(val id: String, val title: String, val group: String)

    var actions: List<Act> = emptyList()
        set(v) { field = v; if (group !in groups) group = groups.firstOrNull().orEmpty(); invalidate() }
    /** The 聊天 tab's name (empty: no chat). */
    var chatTab = ""
    /** 小K's name, on the conversation's title line. */
    var kayName = "小K"
    /** The conversation, ready to draw (who said what, styled). */
    var chat: CharSequence = ""
        set(v) { field = v; resLayout = null; if (group == chatTab) { follow = true }; invalidate() }
    /** Whether 小K has answered at least once (插入 has something to put in). */
    var chatHasAnswer = false
    /** The kinds of action, in the order they first appear, after 聊天. */
    private val groups get() = (if (chatTab.isNotEmpty()) listOf(chatTab) else emptyList()) + actions.map { it.group }.distinct()
    /** Shows the tab [name] (聊天, 改写 …). */
    fun selectGroup(name: String) { if (name in groups) { group = name; resLayout = null; invalidate() } }
    val inChat get() = group == chatTab && chatTab.isNotEmpty()
    /** The kind of action shown. */
    private var group = ""
    var onAction: ((String) -> Unit)? = null
    var onButton: ((String) -> Unit)? = null     // "replace" "insert" "copy" "retry" "setup" "custom" "target:<id>"
    var onFeedback: (() -> Unit)? = null

    /** What will be sent (the selection, or the whole field) and which of the two it is. */
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
        set(v) { if (v != null && field == null) runSince = Motion.now(); field = v; invalidate() }
    /** When the run began, for the seconds shown while waiting. */
    private var runSince = 0L
    var doneTitle = ""
    var status = ""
        set(v) { field = v; invalidate() }
    private var result = ""
    private var diffBase: String? = null

    /** The answer so far; [base] marks what changed against the original (润色/纠错). */
    fun setResult(text: String, base: String?) {
        // a new answer starts at the top; a streaming one follows its last line unless scrolled
        if (result.isEmpty() || !text.startsWith(result)) { resScroll = 0f; follow = true; resScroller.forceFinished(true) }
        result = text; diffBase = base; resLayout = null; invalidate()
    }

    // a long answer scrolls inside its card
    private val resView = RectF()
    private var resScroll = 0f
    private var resMax = 0f
    private var follow = true
    private val resScroller = OverScroller(context)
    private var resFadeTop: LinearGradient? = null
    private var resFadeBottom: LinearGradient? = null
    private var resFadeAt = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ellipsizer = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var resLayout: StaticLayout? = null
    private val hits = Hits()
    private var pressed: String? = null
    private val pulse = Tween(0f)
    private val r = RectF()
    /** The switch's highlight glides between the kinds. */
    private val tabPos = Spring(0f, stiffness = 1100f, dampingRatio = 0.8f)
    private val tabBox = RectF()

    override fun onThemeChanged() { resLayout = null; resFadeTop = null; resFadeBottom = null }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { resLayout = null; resFadeTop = null; resFadeBottom = null }

    private val pad get() = dp(Ui.EDGE + 2f)

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        var again = false
        val ch = dp(Ui.CHIP_H)
        var y = dp(10f)
        if (!needsSetup) {
            // header: 改写 · 翻译 on the left, the model chip on the right
            val pw = chipWidth(provider, chevron = true, textSp = Ui.SMALL)
            r.set(width - pad - pw, y, width - pad, y + ch)
            drawChip(c, r, provider, if (picking) ChipStyle.ACTIVE else ChipStyle.SOFT, pressed = pressed == "provider", chevron = true, textSp = Ui.SMALL)
            hits.add("provider", r, slop = dp(6f))
            again = drawTabs(c, y, ch, r.left - dp(10f)) || again
            y += ch + dp(10f)
        }
        if (needsSetup) { drawSetup(c, y); return }
        if (picking) {
            // 选择模型: every enabled service and model, in a list that scrolls
            if (pick.draw(c, r.apply { set(pad, y, width - pad, height - dp(10f)) }, t, Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f)))) again = true
            if (again) postInvalidateOnAnimation()
            return
        }
        if (inChat) {
            again = drawResult(c, y) || again
            if (again) postInvalidateOnAnimation()
            return
        }
        // once there is an answer (or one is on its way) the actions fold away and it gets the panel
        val folded = result.isNotEmpty() || running != null
        if (!folded) {
            again = drawActions(c, y, ch) || again
            y += actionsHeight(ch) + dp(10f)
        }
        again = drawResult(c, y) || again
        if (again) postInvalidateOnAnimation()
    }

    /** The 改写 · 翻译 switch; true while its highlight moves. */
    private fun drawTabs(c: Canvas, y: Float, h: Float, maxRight: Float): Boolean {
        val t = theme
        val names = groups
        if (names.size < 2) return false
        val segW = dp(64f)
        val box = tabBox.apply { set(pad, y, minOf(pad + segW * names.size, maxRight), y + h) }
        val w = box.width() / names.size
        val cur = names.indexOf(group).coerceAtLeast(0)
        if (tabPos.target != cur.toFloat()) tabPos.animateTo(cur.toFloat())
        if (!Motion.on(prefs)) tabPos.snap(cur.toFloat())
        val moving = tabPos.running
        if (moving) tabPos.step()
        val p = tabPos.value
        bg.color = t.soft
        c.drawRoundRect(box, h / 2, h / 2, bg)
        val inset = dp(3f)
        bg.color = t.accent
        val hl = r.apply { set(box.left + p * w + inset, box.top + inset, box.left + (p + 1) * w - inset, box.bottom - inset) }
        c.drawRoundRect(hl, hl.height() / 2, hl.height() / 2, bg)
        tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY)
        names.forEachIndexed { i, name ->
            val near = (1f - abs(p - i)).coerceIn(0f, 1f)
            tp.color = Colors.blend(t.inkSoft, t.onAccent, near)
            tp.typeface = if (near > 0.5f) Ui.medium else Ui.regular
            val l = box.left + i * w
            drawText(c, name, l + w / 2, box.centerY(), tp)
            hits.add("tab:$name", l, box.top - dp(4f), l + w, box.bottom + dp(4f))
        }
        tp.typeface = Ui.regular
        return moving
    }

    /** Lays the actions of [kind] and 输入指令 out in lines from [y]; calls [place] for each chip. */
    private fun flow(kind: String, y: Float, ch: Float, place: (id: String, title: String, l: Float, t: Float, w: Float) -> Unit): Float {
        val gap = dp(Ui.CHIP_GAP)
        var x = pad; var top = y
        val right = width - pad
        fun put(id: String, title: String, w: Float) {
            if (x > pad && x + w > right) { x = pad; top += ch + gap }
            place(id, title, x, top, w)
            x += w + gap
        }
        for (a in actions) if (a.group == kind) put(a.id, a.title, chipWidth(a.title))
        put("custom", "输入指令", chipWidth("输入指令", icon = "ai"))
        return top + ch - y
    }

    /** The height the actions take: the tallest kind, so switching kinds moves nothing below. */
    private fun actionsHeight(ch: Float): Float = groups.maxOfOrNull { g -> flow(g, 0f, ch) { _, _, _, _, _ -> } } ?: ch

    /** The actions of the kind picked, wrapped onto lines. True while animating. */
    private fun drawActions(c: Canvas, y: Float, ch: Float): Boolean {
        val t = theme
        val ps = if (running != null) {
            if (!pulse.running) { pulse.set(0f); pulse.animateTo(1f, 1100, Motion.LINEAR) }
            sin(pulse.value * Math.PI).toFloat()
        } else 0f
        flow(group, y, ch) { id, title, l, top, w ->
            r.set(l, top, l + w, top + ch)
            when {
                id == "custom" -> drawChip(c, r, title, ChipStyle.PRIMARY, pressed = pressed == id, icon = "ai")
                running == id -> {
                    // the action at work breathes
                    bg.color = Colors.blend(t.accentSoft, t.accent, 0.12f + 0.18f * ps)
                    c.drawRoundRect(r, ch / 2, ch / 2, bg)
                    chipText.textSize = sp(Ui.BODY); chipText.typeface = Ui.medium; chipText.color = t.accent
                    chipText.textAlign = Paint.Align.CENTER
                    drawText(c, title, r.centerX(), r.centerY(), chipText)
                }
                else -> drawChip(c, r, title, ChipStyle.SOFT, pressed = pressed == id)
            }
            hits.add(id, r, slop = dp(3f))
        }
        return running != null
    }

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
        drawText(c, TextUtils.ellipsize("填入自己的 Key，即可使用 Claude、OpenAI、Gemini 等", ellipsizer, card.width() - dp(24f), TextUtils.TruncateAt.END), cx, cardTop + dp(82f), tp)
        val w = chipWidth("去设置", icon = "settings")
        val by = minOf(cardTop + dp(100f), cardBottom - dp(12f) - dp(Ui.CHIP_H))
        r.set(cx - w / 2, by, cx + w / 2, by + dp(Ui.CHIP_H))
        drawChip(c, r, "去设置", ChipStyle.PRIMARY, pressed = pressed == "setup", icon = "settings")
        hits.add("setup", r, slop = dp(6f))
    }

    /** The answer card; true while something in it moves. */
    fun hasResult() = result.isNotEmpty()
    fun resultText() = result

    /** …, growing a dot at a time while something runs. */
    private fun dots(): String = ".".repeat(1 + ((Motion.now() - runSince) / 380 % 3).toInt())

    /** 停止, right-aligned at [right]; returns its left edge. */
    private fun drawStop(c: Canvas, right: Float, top: Float, h: Float): Float {
        val w = chipWidth("停止", icon = "stop", textSp = Ui.SMALL + 1f)
        val sr = RectF(right - w, top, right, top + h)
        drawChip(c, sr, "停止", ChipStyle.SOFT, pressed = pressed == "stop", icon = "stop", textSp = Ui.SMALL + 1f)
        hits.add("stop", sr, slop = dp(5f))
        return sr.left
    }

    /** Placeholder lines with a light sweeping across them: the answer is on its way. */
    private fun shimmer(c: Canvas, l: Float, t0: Float, r0: Float, b: Float) {
        val h = dp(11f); val gap = dp(12f)
        val widths = floatArrayOf(0.94f, 0.82f, 0.6f, 0.88f, 0.45f)
        val sweep = l - (r0 - l) * 0.4f + (r0 - l) * 1.8f * pulse.value
        var y = t0
        for (wf in widths) {
            if (y + h > b) break
            val right = l + (r0 - l) * wf
            bg.shader = android.graphics.LinearGradient(sweep - dp(90f), 0f, sweep + dp(90f), 0f,
                intArrayOf(theme.soft, Colors.blend(theme.soft, theme.accentSoft, 0.9f), theme.soft), floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(l, y, right, y + h, h / 2, h / 2, bg)
            bg.shader = null
            y += h + gap
        }
    }

    private fun drawResult(c: Canvas, top0: Float): Boolean {
        val t = theme
        val card = r.apply { set(pad, top0, width - pad, height - dp(10f)) }
        if (card.height() < dp(48f)) return false
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        bg.color = t.surface
        c.drawRoundRect(card, rad, rad, bg)
        val cardL = card.left; val cardR = card.right; val cardT = card.top; val cardB = card.bottom
        val inner = dp(12f)
        if (inChat) return drawChat(c, cardL, cardT, cardR, cardB, inner)
        if (result.isEmpty() && running != null && status.isEmpty()) {
            // waiting for the first words: what is being done, the seconds, 停止, and lines of
            // text-to-be with a light sweeping across them
            if (!pulse.running) { pulse.set(0f); pulse.animateTo(1f, 1300, Motion.LINEAR) }
            val lineH = dp(28f); val lineT = cardT + dp(8f); val lineCy = lineT + lineH / 2
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.typeface = Ui.medium; tp.color = t.accent
            drawText(c, doneTitle + " · 正在思考" + dots(), cardL + inner, lineCy, tp)
            tp.typeface = Ui.regular; tp.color = t.hint
            val secs = (Motion.now() - runSince) / 1000f
            val stopW = drawStop(c, cardR - dp(8f), lineT, lineH)
            if (secs >= 1f) { tp.textAlign = Paint.Align.RIGHT; drawText(c, String.format("%.0f 秒", secs), stopW - dp(8f), lineCy, tp) }
            shimmer(c, cardL + inner, lineT + lineH + dp(12f), cardR - inner, cardB - dp(12f))
            return true
        }
        if (result.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.SMALL + 1f); tp.color = t.hint
            drawText(c, status.ifEmpty { idleNote() }, card.centerX(), card.centerY(), tp)
            return false
        }
        // title line: ✕ and what was done on the left, the buttons for the answer on the right
        val lineH = dp(28f)
        val lineT = cardT + dp(8f)
        val lineCy = lineT + lineH / 2
        var tx = cardL + inner
        if (running == null) {
            r.set(cardL + dp(6f), lineT, cardL + dp(6f) + lineH, lineT + lineH)
            drawChip(c, r, "", ChipStyle.GHOST, pressed = pressed == "back", icon = "close")
            hits.add("back", r, slop = dp(5f))
            tx = r.right + dp(4f)
        }
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.color = if (running != null) t.accent else t.hint; tp.typeface = Ui.medium
        drawText(c, if (running != null) "$doneTitle · 生成中" + dots() else doneTitle, tx, lineCy, tp)
        tp.typeface = Ui.regular
        // still writing: 停止 keeps what came so far
        if (running != null) { drawStop(c, cardR - dp(8f), lineT, lineH); if (!pulse.running) { pulse.set(0f); pulse.animateTo(1f, 1300, Motion.LINEAR) } }
        if (running == null) {
            var bx = cardR - dp(8f)
            for ((id, label) in BUTTONS) {
                val primary = id == "replace"
                val w = chipWidth(label, textSp = Ui.SMALL + 1f)
                r.set(bx - w, lineT, bx, lineT + lineH)
                drawChip(c, r, label, if (primary) ChipStyle.PRIMARY else ChipStyle.SOFT, pressed = pressed == id, textSp = Ui.SMALL + 1f)
                hits.add(id, r, slop = dp(5f))
                bx = r.left - dp(6f)
            }
        }
        // the text, in a window that scrolls when the answer is longer than the card
        resView.set(cardL + inner, lineT + lineH + dp(6f), cardR - inner, cardB - dp(10f))
        val lay = resLayout ?: run {
            body.textSize = sp(Ui.BODY + 1f); body.color = t.ink
            val text = marked(result, diffBase)
            StaticLayout.Builder.obtain(text, 0, text.length, body, resView.width().toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
                .also { resLayout = it }
        }
        resMax = (lay.height - resView.height()).coerceAtLeast(0f)
        var moving = false
        if (resScroller.computeScrollOffset()) { resScroll = resScroller.currY.toFloat(); moving = !resScroller.isFinished }
        if (running != null && follow) resScroll = resMax
        resScroll = resScroll.coerceIn(0f, resMax)
        c.save(); c.clipRect(resView); c.translate(resView.left, resView.top - resScroll); lay.draw(c); c.restore()
        if (resMax > 0f) {
            // soft edges where the text goes on, and a thin bar for where we are in it
            val fade = dp(14f)
            if (resFadeAt != resView.top) { resFadeTop = null; resFadeBottom = null; resFadeAt = resView.top }
            val clear = Colors.alpha(t.surface, 0)
            if (resScroll > 1f) {
                bg.shader = resFadeTop ?: LinearGradient(0f, resView.top, 0f, resView.top + fade, t.surface, clear, Shader.TileMode.CLAMP).also { resFadeTop = it }
                c.drawRect(resView.left, resView.top, resView.right, resView.top + fade, bg); bg.shader = null
            }
            if (resScroll < resMax - 1f) {
                bg.shader = resFadeBottom ?: LinearGradient(0f, resView.bottom - fade, 0f, resView.bottom, clear, t.surface, Shader.TileMode.CLAMP).also { resFadeBottom = it }
                c.drawRect(resView.left, resView.bottom - fade, resView.right, resView.bottom, bg); bg.shader = null
            }
            val h = (resView.height() * resView.height() / lay.height).coerceAtLeast(dp(16f))
            val y = resView.top + (resView.height() - h) * (resScroll / resMax)
            bg.color = t.divider
            c.drawRoundRect(cardR - dp(6f), y, cardR - dp(3.5f), y + h, dp(2f), dp(2f), bg)
        }
        return moving || running != null
    }

    /** The conversation card: 小K and its name, 回话 · 插入, and what was said, newest at the bottom. */
    private fun drawChat(c: Canvas, cardL: Float, cardT: Float, cardR: Float, cardB: Float, inner: Float): Boolean {
        val t = theme
        val lineH = dp(30f)
        val lineT = cardT + dp(8f)
        val lineCy = lineT + lineH / 2
        val face = dp(22f)
        val wait = KayFace.draw(c, cardL + inner + face / 2, lineCy, face, t.inkSoft, t.accentSoft, 0x8CF08C9B.toInt(), Motion.now(), java.time.LocalTime.now().hour)
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL + 1f); tp.typeface = Ui.medium; tp.color = if (running != null) t.accent else t.inkSoft
        drawText(c, if (running != null) "$kayName 在想" + dots() else kayName, cardL + inner + face + dp(8f), lineCy, tp)
        tp.typeface = Ui.regular
        var bx = cardR - dp(8f)
        if (running != null) bx = drawStop(c, bx, lineT, lineH) - dp(6f)
        else {
            val w = chipWidth("回话", textSp = Ui.SMALL + 1f)
            r.set(bx - w, lineT, bx, lineT + lineH)
            drawChip(c, r, "回话", ChipStyle.PRIMARY, pressed = pressed == "reply", textSp = Ui.SMALL + 1f)
            hits.add("reply", r, slop = dp(5f))
            bx = r.left - dp(6f)
            if (chatHasAnswer) {
                val iw = chipWidth("插入", textSp = Ui.SMALL + 1f)
                r.set(bx - iw, lineT, bx, lineT + lineH)
                drawChip(c, r, "插入", ChipStyle.SOFT, pressed = pressed == "insert", textSp = Ui.SMALL + 1f)
                hits.add("insert", r, slop = dp(5f))
            }
        }
        resView.set(cardL + inner, lineT + lineH + dp(6f), cardR - inner, cardB - dp(10f))
        if (chat.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.SMALL + 1f); tp.color = t.hint
            drawText(c, status.ifEmpty { "点「回话」，想说什么都行" }, resView.centerX(), resView.centerY(), tp)
            return wait == 0L || running != null
        }
        val lay = resLayout ?: run {
            body.textSize = sp(Ui.BODY + 1f); body.color = t.ink
            StaticLayout.Builder.obtain(chat, 0, chat.length, body, resView.width().toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(dp(2f), 1.1f).build().also { resLayout = it }
        }
        resMax = (lay.height - resView.height()).coerceAtLeast(0f)
        if (resScroller.computeScrollOffset()) resScroll = resScroller.currY.toFloat()
        if (follow) resScroll = resMax
        resScroll = resScroll.coerceIn(0f, resMax)
        c.save(); c.clipRect(resView); c.translate(resView.left, resView.top - resScroll); lay.draw(c); c.restore()
        if (wait > 0 && running == null) postInvalidateDelayed(wait)
        return wait == 0L || running != null || !resScroller.isFinished
    }

    /** What an action will work on, said where the answer will appear. */
    private fun idleNote(): String = when {
        source.isBlank() -> "输入框是空的，可以用「输入指令」直接写"
        sourceLabel == "选中" -> "将处理选中的 ${source.length} 个字"
        else -> "将处理输入框里的全部文字；选中一段则只处理那一段"
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
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var resDragY = -1f
    private var resDownY = 0f
    private var resDragging = false

    /** Drags that start on a long answer scroll it. True when the event was used. */
    private fun scrollResult(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (picking || resMax <= 0f || (result.isEmpty() && !inChat) || !resView.contains(e.x, e.y)) { resDragY = -1f; return false }
                resScroller.forceFinished(true)
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                resDragY = e.y; resDownY = e.y; resDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resDragY < 0f) return false
                vt?.addMovement(e)
                if (!resDragging && abs(e.y - resDownY) > slop) resDragging = true
                if (resDragging) { follow = false; resScroll = (resScroll - (e.y - resDragY)).coerceIn(0f, resMax); invalidate() }
                resDragY = e.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resDragY < 0f) return false
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                if (resDragging && abs(v) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                    resScroller.fling(0, resScroll.toInt(), 0, -v.toInt(), 0, 0, 0, resMax.toInt())
                }
                vt?.recycle(); vt = null; resDragY = -1f; resDragging = false
                invalidate()
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scrollResult(e)) return true
        if (picking && (pick.busy || (e.actionMasked == MotionEvent.ACTION_DOWN && pick.contains(e.x, e.y)))) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFeedback?.invoke()
            pick.onTouch(e)?.let { t -> picking = false; onButton?.invoke("target:$t") }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                if (pressed != null) onFeedback?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // a finger that slides off what it pressed lets go of it
                if (pressed != null && hits.at(e.x, e.y) != pressed) { pressed = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                when {
                    p == null -> picking = false
                    p == "provider" -> { picking = !picking; if (picking) pick.reveal() }
                    picking -> picking = false
                    p.startsWith("tab:") -> { group = p.removePrefix("tab:"); resLayout = null; onButton?.invoke("group:$group") }
                    // ✕: the answer goes, the actions come back
                    p == "back" -> { result = ""; diffBase = null; resLayout = null; status = "" }
                    p in BUTTON_IDS -> onButton?.invoke(p)
                    running == null -> onAction?.invoke(p)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private companion object {
        /** Result buttons, drawn right to left. */
        val BUTTONS = listOf("replace" to "替换", "insert" to "插入", "copy" to "复制", "retry" to "重试")
        val BUTTON_IDS = setOf("replace", "insert", "copy", "retry", "setup", "custom", "stop", "reply")
    }
}
