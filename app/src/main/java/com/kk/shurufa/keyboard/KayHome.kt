package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 小K's page, where the keyboard was when the 小K key is tapped: 小K on the left, large and
 * alive, and on the right the three things it does (改写, 翻译, 聊天). Picking one brings the
 * keyboard back with 小K's strip above it.
 *
 * Poked, 小K does something (wriggles, hops, wanders off a little, spins, shakes) and says
 * something; its words come out a character at a time in a bubble over it. Left alone it gets
 * chatty, and when it has run out of things to say it starts telling itself cold jokes, until
 * something is done.
 */
@SuppressLint("ViewConstructor")
class KayHome(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** "rewrite", "translate", "chat", "all" (the conversation), "setup". */
    var onChoose: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null
    /** 小K was poked (for a buzz). */
    var onPoke: (() -> Unit)? = null

    var kayName = "小K"
    var line = ""
    /** An AI service is set up (翻译 works without one). */
    var aiReady = true
    var provider = ""
    var place: String? = null
    var placeOk: Boolean? = null
    /** Turns in the conversation so far (a way back to it when there are any). */
    var talks = 0
    /** Lines an AI wrote for 小K today, mixed in with its own. */
    var extraLines: List<String> = emptyList()

    private class Card(val id: String, val title: String, val sub: String, val icon: String)
    private val cards get() = listOf(
        Card("rewrite", "改写", if (aiReady) "润色 · 纠错 · 正式…" else "先设置 AI 服务", "ai"),
        Card("translate", "翻译", "中 · 英 · 俄", "translate"),
        Card("chat", "聊天", if (aiReady) "跟${kayName}说说话" else "先设置 AI 服务", "emoji"),
    )

    private val appear = Tween(1f)
    private val pressT = Tween(0f)
    private var pressed: String? = null
    private val hits = Hits()
    private val r = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rnd = java.util.Random()

    // ---- 小K's own motion on this page ----

    /** Where it is, as an offset from its place (px), moving smoothly. */
    private val posX = Tween(0f)
    private val posY = Tween(0f)
    private var facingLeft = false
    private var act: String? = null
    private var actAt = 0L
    private var actMs = 0L
    private val area = RectF()

    // ---- what it says ----

    private var bubble = ""
    private var bubbleAt = 0L
    private var bubbleUntil = 0L
    private var chatty = 0
    private var joking = false
    private val idleTick = Runnable { idle() }

    /** The page comes into view: the cascade plays, and 小K starts with its line of the day. */
    fun entered() {
        if (Motion.on(prefs)) { appear.set(0f); appear.animateTo(1f, dur(520), Motion.LINEAR) } else appear.set(1f)
        posX.set(0f); posY.set(0f); act = null; chatty = 0; joking = false
        if (line.isNotEmpty()) say(line, 5000, delay = 350) else bubble = ""
        quietAgain(9000)
        invalidate()
    }

    /** Something was done: the chatter waits again. */
    private fun quietAgain(ms: Long = 8000) {
        removeCallbacks(idleTick)
        postDelayed(idleTick, ms)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(idleTick)
    }

    private fun say(text: String, holdMs: Long = 3800, delay: Long = 0) {
        bubble = text
        bubbleAt = Motion.now() + delay
        bubbleUntil = bubbleAt + text.length * CHAR_MS + holdMs
        invalidate()
    }

    /** Nobody has done anything for a while: it talks; out of things to say, it tells jokes. */
    private fun idle() {
        if (!isAttachedToWindow) return
        when {
            joking || chatty >= CHATTY_BEFORE_JOKES -> {
                if (!joking) { joking = true; say(SharkTalk.JOKES_START, 2400); move("hop"); quietAgain(3800); return }
                val j = SharkTalk.deal(SharkTalk.JOKES)
                val (setUp, answer) = j.substringBefore('|') to j.substringAfter('|', "")
                say(setUp, 1800)
                if (answer.isNotEmpty()) postDelayed({ if (isAttachedToWindow && joking) { say("$setUp\n——$answer", 4200); move(listOf("wiggle", "spin", "hop").random()) } }, setUp.length * CHAR_MS + 2200)
                quietAgain(setUp.length * CHAR_MS + answer.length * CHAR_MS + 9000)
            }
            else -> {
                chatty++
                say(SharkTalk.deal(SharkTalk.CHATTY, extraLines))
                if (rnd.nextInt(10) < 5) move(listOf("roam", "roam", "shake", "hop").random())
                quietAgain(6500L + rnd.nextInt(2500))
            }
        }
    }

    /** Poked: something to do, and something to say. */
    private fun poke() {
        joking = false; chatty = 0
        move(listOf("wiggle", "hop", "roam", "spin", "shake", "dance").random())
        say(SharkTalk.deal(SharkTalk.POKED, extraLines))
        onPoke?.invoke()
        quietAgain()
    }

    private fun move(kind: String) {
        if (!Motion.on(prefs)) return
        act = kind; actAt = Motion.now()
        actMs = when (kind) { "wiggle" -> 700L; "hop" -> 560L; "spin" -> 520L; "shake" -> 620L; "dance" -> 1100L; else -> 700L }
        if (kind == "roam" && !area.isEmpty) {
            // somewhere else in its corner, facing where it goes
            val tx = (rnd.nextFloat() - 0.5f) * area.width() * 0.55f
            val ty = (rnd.nextFloat() - 0.5f) * area.height() * 0.25f
            facingLeft = tx < posX.value
            posX.animateTo(tx, actMs, Motion.MOVE); posY.animateTo(ty, actMs, Motion.MOVE)
        }
        invalidate()
    }

    /** How far along item [i] of the cascade is (0 to 1), eased. */
    private fun stage(i: Int): Float {
        val p = ((appear.value - i * 0.12f) / 0.55f).coerceIn(0f, 1f)
        return Motion.ENTER.getInterpolation(p)
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        val now = Motion.now()
        var again = appear.running || pressT.running || posX.running || posY.running
        val pad = dp(Ui.EDGE + 2f)
        val w = width.toFloat(); val h = height.toFloat()
        val split = w * 0.46f
        // the right side: a quiet line on top (where the internet sees us, the model), the three below
        tp.textSize = sp(Ui.SMALL - 1f); tp.typeface = Ui.regular; tp.color = t.hint
        val top = dp(18f)
        val info = listOfNotNull(place, provider.takeIf { it.isNotEmpty() }).joinToString("  ·  ")
        if (info.isNotEmpty()) {
            tp.textAlign = Paint.Align.RIGHT
            val shown = TextUtils.ellipsize(info, tp, w - pad - split - dp(14f), TextUtils.TruncateAt.END).toString()
            c.drawText(shown, w - pad, top, tp)
            if (place != null) {
                val dx = w - pad - tp.measureText(shown) - dp(7f)
                bg.color = when (placeOk) { true -> 0xFF4CAF7A.toInt(); false -> 0xFFE0A33A.toInt(); null -> t.hint }
                c.drawCircle(dx, top - dp(4f), dp(2.6f), bg)
            }
        }
        if (talks > 0) {
            tp.textAlign = Paint.Align.LEFT; tp.color = t.accent
            val s = "对话 $talks 句 ›"
            c.drawText(s, pad, top, tp)
            hits.add("all", pad - dp(6f), top - dp(16f), pad + tp.measureText(s) + dp(6f), top + dp(8f))
        }
        val colL = split; val colR = w - pad
        val colT = dp(28f); val colB = h - dp(12f)
        val gap = dp(8f)
        val rowH = (colB - colT - 2 * gap) / 3
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        for ((i, card) in cards.withIndex()) {
            val s = stage(1 + i)
            if (s <= 0f) continue
            val y = colT + i * (rowH + gap)
            r.set(colL, y, colR, y + rowH)
            val press = if (pressed == card.id) pressT.value else 0f
            val sc = 1f - 0.03f * press
            c.save()
            c.translate((1 - s) * dp(20f), 0f)
            c.scale(sc, sc, r.centerX(), r.centerY())
            bg.color = if (pressed == card.id) Colors.blend(t.surface, t.accentSoft, 0.5f * press) else t.surface
            bg.alpha = (255 * s).toInt()
            c.drawRoundRect(r, rad, rad, bg)
            val dim = !aiReady && card.id != "translate"
            ip.color = Colors.fade(if (dim) t.hint else t.accent, s); ip.strokeWidth = dp(1.7f)
            val iconX = r.left + dp(24f)
            Icons.draw(c, card.icon, iconX, r.centerY(), dp(22f), ip)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.BODY + 1f); tp.typeface = Ui.medium
            tp.color = Colors.fade(if (dim) t.inkSoft else t.ink, s)
            val tx = iconX + dp(22f)
            val twoLines = rowH >= dp(52f)
            c.drawText(card.title, tx, if (twoLines) r.centerY() - dp(2f) else r.centerY() + sp(Ui.BODY) * 0.35f, tp)
            if (twoLines) {
                tp.textSize = sp(Ui.SMALL - 1f); tp.typeface = Ui.regular; tp.color = Colors.fade(t.hint, s)
                val sub = TextUtils.ellipsize(card.sub, tp, r.right - tx - dp(10f), TextUtils.TruncateAt.END).toString()
                c.drawText(sub, tx, r.centerY() + dp(16f), tp)
            }
            c.restore()
            hits.add(card.id, r)
        }
        // the left side: 小K, and its words over it
        area.set(pad, colT, split - dp(10f), colB)
        val s0 = stage(0)
        val face = minOf(dp(92f), area.width() * 0.62f, area.height() * 0.52f)
        val homeX = area.centerX(); val homeY = area.bottom - face * 0.62f
        var x = homeX + posX.value; var y = homeY + posY.value + (1 - s0) * dp(18f)
        var rot = 0f; var scaleX = 1f
        val a = act
        if (a != null) {
            val p = ((now - actAt).toFloat() / actMs).coerceIn(0f, 1f)
            when (a) {
                "wiggle" -> rot = 12f * sin(2 * PI.toFloat() * 3 * p) * (1 - p)
                "hop" -> y -= dp(20f) * abs(sin(2 * PI.toFloat() * p))
                "spin" -> scaleX = cos(PI.toFloat() * 2 * p)
                "shake" -> x += dp(7f) * sin(2 * PI.toFloat() * 4 * p) * (1 - p)
                "dance" -> { y -= dp(12f) * abs(sin(2 * PI.toFloat() * 2 * p)); rot = 10f * sin(2 * PI.toFloat() * 2 * p) }
                "roam" -> y -= dp(5f) * sin(PI.toFloat() * p)
            }
            if (p >= 1f) act = null else again = true
        }
        val (pose0, wait) = SharkLife.frame(now, java.time.LocalTime.now().hour, still = false)
        // talking while the words come out: the mouth opens and closes
        val typing = bubble.isNotEmpty() && now >= bubbleAt && now < bubbleAt + bubble.length * CHAR_MS
        var pose = pose0.copy(flip = pose0.flip xor facingLeft)
        if (typing && pose.face == Shark.Face.CALM) pose = pose.copy(face = if ((now / 140) % 2 == 0L) Shark.Face.HAPPY else Shark.Face.CALM)
        c.save()
        c.translate(x, y); c.rotate(rot); c.scale(if (abs(scaleX) < 0.08f) 0.08f else abs(scaleX), 1f)
        Shark.draw(c, 0f, 0f, face, pose, alpha = (255 * s0).toInt())
        c.restore()
        hits.add("poke", x - face * 0.7f, y - face * 0.65f, x + face * 0.7f, y + face * 0.6f)
        if (typing) again = true
        // the bubble, its words a character at a time
        if (bubble.isNotEmpty() && now >= bubbleAt && now < bubbleUntil) {
            val shown = ((now - bubbleAt) / CHAR_MS).toInt().coerceIn(1, bubble.length)
            val text = bubble.substring(0, shown)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL + 1f); tp.typeface = Ui.regular; tp.color = t.ink
            val maxW = (area.width() + dp(6f)).toInt()
            val full = StaticLayout.Builder.obtain(bubble, 0, bubble.length, tp, maxW).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
            var lw = 0f
            for (i in 0 until full.lineCount) lw = maxOf(lw, full.getLineWidth(i))
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
            val bw = lw + dp(20f); val bh = full.height + dp(12f)
            val fade = ((bubbleUntil - now) / 250f).coerceIn(0f, 1f) * ((now - bubbleAt) / 160f).coerceIn(0f, 1f)
            val minX = pad - dp(4f)
            val bx = (x - bw / 2).coerceIn(minX, maxOf(minX, split - dp(6f) - bw))
            val by = (y - face * 0.62f - dp(8f) - bh).coerceAtLeast(dp(24f))
            bg.color = t.surface; bg.alpha = (255 * fade).toInt()
            r.set(bx, by, bx + bw, by + bh)
            c.drawRoundRect(r, dp(12f), dp(12f), bg)
            val tailX = x.coerceIn(r.left + dp(12f), r.right - dp(12f))
            val path = Path().apply { moveTo(tailX - dp(5f), r.bottom - 0.5f); lineTo(tailX, r.bottom + dp(6f)); lineTo(tailX + dp(5f), r.bottom - 0.5f); close() }
            c.drawPath(path, bg)
            tp.alpha = (255 * fade).toInt()
            c.save(); c.translate(bx + dp(10f), by + dp(6f)); lay.draw(c); c.restore()
            tp.alpha = 255
            if (fade < 1f) again = true
        }
        if (again) postInvalidateOnAnimation()
        else {
            val next = listOf(wait, if (bubble.isNotEmpty() && now < bubbleUntil) bubbleUntil - 240 - now else Long.MAX_VALUE).filter { it > 0 }.minOrNull() ?: wait
            if (next == 0L) postInvalidateOnAnimation() else invalidateIn(next.coerceAtMost(60_000L))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                if (pressed != null) {
                    onFeedback?.invoke()
                    if (pressed != "poke" && pressed != "all") { pressT.set(0f); pressT.animateTo(1f, dur(90), Motion.ENTER) }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                if (p != null && hits.at(e.x, e.y) == p) {
                    when {
                        p == "poke" -> poke()
                        !aiReady && (p == "rewrite" || p == "chat") -> onChoose?.invoke("setup")
                        else -> onChoose?.invoke(p)
                    }
                } else quietAgain()
                if (Motion.on(prefs)) pressT.animateTo(0f, dur(160), Motion.ENTER) else pressT.set(0f)
                postDelayed({ pressed = null; invalidate() }, dur(160))
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private companion object {
        /** A character of a bubble comes out every this many ms. */
        const val CHAR_MS = 55L
        const val CHATTY_BEFORE_JOKES = 5
    }
}
