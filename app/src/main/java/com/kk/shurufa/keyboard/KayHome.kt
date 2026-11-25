package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 小K's page, where the keyboard was when the 小K key is tapped: 小K on the left, large and
 * alive, its words in a bubble over it; on the right, small, the three things it does (改写,
 * 翻译, 聊天). Picking one brings the keyboard back with 小K's strip above it.
 *
 * It never stands still: it breathes (a slow rise and fall, by fractions of a pixel), leans into
 * where it goes, crouches before a hop and squashes when it lands, and always comes back to its
 * place. Poked, it does something and answers; left alone it talks in little threads, and then
 * tells cold jokes, until something is done.
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
    /** Lines an AI wrote for 小K today, said among its own. */
    var extraLines: List<String> = emptyList()

    private class Card(val id: String, val title: String, val sub: String, val icon: String)
    private val cards get() = listOf(
        Card("rewrite", "改写", if (aiReady) "润色 · 纠错" else "先设置 AI", "ai"),
        Card("translate", "翻译", "中 · 英 · 俄", "translate"),
        Card("chat", "聊天", if (aiReady) "说说话" else "先设置 AI", "emoji"),
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

    // ---- 小K's motion on this page ----

    /** Where it is, as an offset from its place (px). */
    private val posX = Tween(0f)
    private val posY = Tween(0f)
    private var facingLeft = false
    private var act: String? = null
    private var actAt = 0L
    private var actMs = 0L
    /** When it goes back to its place after wandering off. */
    private var homeAt = 0L
    private var lean = 0f
    private var lastX = 0f
    private var lastFrame = 0L
    private val area = RectF()

    // ---- what it says ----

    private var bubble = ""
    /** Characters of [bubble] already said (a joke's answer goes on after its set-up). */
    private var bubbleFrom = 0
    private var bubbleAt = 0L
    private var bubbleUntil = 0L
    private var bubblePop = 0L
    private var thread: List<String> = emptyList()
    private var threadAt = 0
    private var threads = 0
    private var joking = false
    private var pokes = 0
    private val idleTick = Runnable { idle() }

    /** The page comes into view: the cascade plays, and 小K starts with its line of the day. */
    fun entered() {
        if (Motion.on(prefs)) { appear.set(0f); appear.animateTo(1f, dur(520), Motion.LINEAR) } else appear.set(1f)
        posX.set(0f); posY.set(0f); act = null; homeAt = 0L; facingLeft = false
        thread = emptyList(); threadAt = 0; threads = 0; joking = false; pokes = 0
        bubble = ""
        if (line.isNotEmpty()) postDelayed({ if (isAttachedToWindow) say(line, 4500) }, 380)
        quietAgain(9000)
        invalidate()
    }

    private fun quietAgain(ms: Long) { removeCallbacks(idleTick); postDelayed(idleTick, ms) }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(idleTick)
    }

    /** [text] in the bubble, typed out from character [from] (what is before it stays). */
    private fun say(text: String, holdMs: Long = 3200, from: Int = 0) {
        val now = Motion.now()
        if (from == 0 || bubble.isEmpty() || now > bubbleUntil) bubblePop = now
        bubble = text; bubbleFrom = from.coerceAtMost(text.length)
        bubbleAt = now
        bubbleUntil = now + (text.length - bubbleFrom) * CHAR_MS + holdMs
        invalidate()
    }

    private fun sayTime(text: String) = (text.length * CHAR_MS)

    /** Nobody has done anything for a while: it talks in threads; out of threads, it tells jokes. */
    private fun idle() {
        if (!isAttachedToWindow) return
        if (threadAt < thread.size) {
            // the thread goes on: the next line follows the last after a breath
            val l = thread[threadAt++]
            say(l)
            if (l == "……") move("wiggle")
            quietAgain(sayTime(l) + 1600L + rnd.nextInt(900))
            return
        }
        if (!joking && threads < THREADS_BEFORE_JOKES) {
            val extra = extraLines.map { listOf(it) }
            thread = SharkTalk.deal(SharkTalk.THREADS, extra); threadAt = 0; threads++
            if (rnd.nextInt(10) < 4) move(listOf("roam", "hop", "shake").random())
            idle()
            return
        }
        if (!joking) {
            joking = true
            val l = SharkTalk.deal(SharkTalk.JOKES_START)
            say(l, 1800); move("hop")
            quietAgain(sayTime(l) + 2200)
            return
        }
        val j = SharkTalk.deal(SharkTalk.JOKES)
        val setUp = j.substringBefore('|'); val answer = j.substringAfter('|', "")
        say(setUp, 6000)
        val pause = sayTime(setUp) + 1900L
        postDelayed({
            if (!isAttachedToWindow || !joking) return@postDelayed
            val both = "$setUp\n——$answer"
            say(both, 3600, from = setUp.length)
            move(listOf("wiggle", "hop", "turn").random())
            if (rnd.nextInt(10) < 5) postDelayed({ if (isAttachedToWindow && joking) say(SharkTalk.deal(SharkTalk.JOKE_AFTER), 2600) }, sayTime(answer) + 2600L)
        }, pause)
        quietAgain(pause + sayTime(answer) + 9000L)
    }

    /** Poked: something to do, and an answer that knows how often it was poked. */
    private fun poke() {
        val inThread = threadAt in 1 until thread.size
        pokes++
        joking = false
        move(listOf("wiggle", "hop", "roam", "turn", "shake", "dance").random())
        val tier = SharkTalk.POKED[(pokes - 1).coerceAtMost(SharkTalk.POKED.size - 1)]
        say(if (inThread && rnd.nextInt(10) < 6) SharkTalk.deal(SharkTalk.INTERRUPTED) else SharkTalk.deal(tier), 2800)
        onPoke?.invoke()
        quietAgain(6500)
    }

    private fun move(kind: String) {
        if (!Motion.on(prefs)) return
        val now = Motion.now()
        act = kind; actAt = now
        actMs = when (kind) { "wiggle" -> 760L; "hop" -> 640L; "turn" -> 620L; "shake" -> 640L; "dance" -> 1200L; "home" -> 820L; else -> 820L }
        when (kind) {
            "roam" -> {
                // somewhere else in its corner, facing where it goes, back home in a while
                val maxX = (area.width() / 2 - dp(36f)).coerceAtLeast(0f)
                val tx = (rnd.nextFloat() * 2 - 1) * maxX
                val ty = -rnd.nextFloat() * area.height() * 0.18f
                facingLeft = tx < posX.value
                posX.animateTo(tx, actMs, Motion.MOVE); posY.animateTo(ty, actMs, Motion.MOVE)
                homeAt = now + actMs + 2600L + rnd.nextInt(1800)
            }
            "home" -> {
                facingLeft = posX.value > 0f
                posX.animateTo(0f, actMs, Motion.MOVE); posY.animateTo(0f, actMs, Motion.MOVE)
                homeAt = 0L
            }
            "turn" -> homeAt = if (posX.value != 0f) homeAt else now + 2400L
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
        val pad = dp(Ui.EDGE + 2f)
        val w = width.toFloat(); val h = height.toFloat()
        // the right side: narrow; a quiet line on top (where the internet sees us, the model), the three below
        val colW = minOf(dp(150f), w * 0.36f)
        val colR = w - pad; val colL = colR - colW
        tp.textSize = sp(Ui.SMALL - 1f); tp.typeface = Ui.regular; tp.color = t.hint
        val top = dp(18f)
        val info = listOfNotNull(place, provider.takeIf { it.isNotEmpty() }).joinToString("  ·  ")
        if (info.isNotEmpty()) {
            tp.textAlign = Paint.Align.RIGHT
            val shown = TextUtils.ellipsize(info, tp, w * 0.6f, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, colR, top, tp)
            if (place != null) {
                val dx = colR - tp.measureText(shown) - dp(7f)
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
        val gap = dp(8f)
        val rowH = minOf(dp(50f), (h - dp(40f) - 2 * gap) / 3)
        val colT = dp(28f) + ((h - dp(40f)) - 3 * rowH - 2 * gap) / 2
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        for ((i, card) in cards.withIndex()) {
            val s = stage(1 + i)
            if (s <= 0f) continue
            val y = colT + i * (rowH + gap)
            r.set(colL, y, colR, y + rowH)
            val press = if (pressed == card.id) pressT.value else 0f
            val sc = 1f - 0.04f * press
            c.save()
            c.translate((1 - s) * dp(20f), 0f)
            c.scale(sc, sc, r.centerX(), r.centerY())
            bg.color = if (pressed == card.id) Colors.blend(t.surface, t.accentSoft, 0.5f * press) else t.surface
            bg.alpha = (255 * s).toInt()
            c.drawRoundRect(r, rad, rad, bg)
            val dim = !aiReady && card.id != "translate"
            ip.color = Colors.fade(if (dim) t.hint else t.accent, s); ip.strokeWidth = dp(1.6f)
            val iconX = r.left + dp(18f)
            Icons.draw(c, card.icon, iconX, r.centerY(), dp(19f), ip)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.BODY); tp.typeface = Ui.medium
            tp.color = Colors.fade(if (dim) t.inkSoft else t.ink, s)
            val tx = iconX + dp(17f)
            c.drawText(card.title, tx, r.centerY() - dp(1f), tp)
            tp.textSize = sp(Ui.SMALL - 2f); tp.typeface = Ui.regular; tp.color = Colors.fade(t.hint, s)
            c.drawText(TextUtils.ellipsize(card.sub, tp, r.right - tx - dp(8f), TextUtils.TruncateAt.END).toString(), tx, r.centerY() + dp(14f), tp)
            c.restore()
            hits.add(card.id, r)
        }
        // the left side: 小K, its words over it
        area.set(pad, dp(26f), colL - dp(10f), h - dp(10f))
        if (homeAt in 1..now && act == null) move("home")
        val s0 = stage(0)
        val face = minOf(dp(96f), area.width() * 0.5f, area.height() * 0.5f)
        val homeX = area.centerX(); val homeY = area.bottom - face * 0.58f
        // breathing: a slow rise and fall, never quite still
        val breath = sin(2 * PI.toFloat() * (now % 2600L) / 2600f)
        var x = homeX + posX.value
        var y = homeY + posY.value + (1 - s0) * dp(18f) + breath * dp(2.2f)
        var rot = 0f; var sx = 1f; var sy = 1f + breath * 0.015f
        val a = act
        if (a != null) {
            val p = ((now - actAt).toFloat() / actMs).coerceIn(0f, 1f)
            fun hop(q: Float, height: Float) {
                // crouch, spring up stretched, land squashed, settle
                when {
                    q < 0.18f -> { val k = q / 0.18f; sy *= 1f - 0.12f * k; sx *= 1f + 0.08f * k }
                    q < 0.78f -> { val k = (q - 0.18f) / 0.6f; y -= height * sin(PI.toFloat() * k); sy *= 1f + 0.08f * sin(PI.toFloat() * k); sx *= 1f - 0.05f * sin(PI.toFloat() * k) }
                    else -> { val k = (q - 0.78f) / 0.22f; val d = sin(PI.toFloat() * k); sy *= 1f - 0.1f * d; sx *= 1f + 0.07f * d }
                }
            }
            when (a) {
                "wiggle" -> { rot = 11f * sin(2 * PI.toFloat() * 2.5f * p) * (1 - p); sx *= 1f + 0.03f * sin(2 * PI.toFloat() * 5 * p) * (1 - p) }
                "hop" -> hop(p, dp(22f))
                "turn" -> { hop(p, dp(10f)); if (p >= 0.5f && !turned) { facingLeft = !facingLeft; turned = true } }
                "shake" -> { x += dp(7f) * sin(2 * PI.toFloat() * 3.5f * p) * (1 - p); rot = -4f * sin(2 * PI.toFloat() * 3.5f * p) * (1 - p) }
                "dance" -> { hop((p * 2f) % 1f, dp(12f)); rot = 9f * sin(2 * PI.toFloat() * 2 * p) }
                "roam", "home" -> y -= dp(6f) * sin(PI.toFloat() * p)
            }
            if (p >= 1f) { act = null; turned = false }
        }
        // leaning into where it goes, smoothed
        val dt = (now - lastFrame).coerceIn(1L, 100L).toFloat()
        val v = if (lastFrame == 0L) 0f else (x - lastX) / dt
        lean += ((v * 18f).coerceIn(-12f, 12f) - lean) * 0.25f
        lastX = x; lastFrame = now
        rot += lean
        val (pose0, _) = SharkLife.frame(now, java.time.LocalTime.now().hour, still = true)
        val typing = bubble.isNotEmpty() && now < bubbleAt + (bubble.length - bubbleFrom) * CHAR_MS
        var pose = pose0.copy(flip = pose0.flip xor facingLeft)
        // talking, the mouth opens and closes; a card pressed, it looks over there
        if (typing && pose.face == Shark.Face.CALM) pose = pose.copy(face = if ((now / 150) % 2 == 0L) Shark.Face.HAPPY else Shark.Face.CALM)
        if (pressed != null && pressed != "poke" && pose.look == 0) pose = pose.copy(look = if (facingLeft) -1 else 1)
        c.save()
        c.translate(x, y + face * 0.4f); c.rotate(rot); c.scale(sx, sy); c.translate(0f, -face * 0.4f)
        Shark.drawCrisp(c, 0f, 0f, face, pose, alpha = (255 * s0).toInt())
        c.restore()
        hits.add("poke", x - face * 0.7f, y - face * 0.65f, x + face * 0.7f, y + face * 0.6f)
        // the bubble, its words a character at a time
        if (bubble.isNotEmpty() && now < bubbleUntil) {
            val shown = (bubbleFrom + ((now - bubbleAt) / CHAR_MS).toInt()).coerceIn(1, bubble.length)
            val text = bubble.substring(0, shown)
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL + 1f); tp.typeface = Ui.regular; tp.color = t.ink
            val maxW = (area.width() - dp(16f)).toInt().coerceAtLeast(1)
            val full = StaticLayout.Builder.obtain(bubble, 0, bubble.length, tp, maxW).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
            var lw = 0f
            for (i in 0 until full.lineCount) lw = maxOf(lw, full.getLineWidth(i))
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
            val bw = lw + dp(20f); val bh = full.height + dp(12f)
            val fade = ((bubbleUntil - now) / 260f).coerceIn(0f, 1f)
            val pop = Motion.POP.getInterpolation(((now - bubblePop) / 220f).coerceIn(0f, 1f))
            val minX = area.left
            val bx = (x - bw / 2).coerceIn(minX, maxOf(minX, area.right - bw))
            val by = (y - face * 0.6f - dp(10f) - bh).coerceAtLeast(dp(24f))
            val tailX = x.coerceIn(bx + dp(12f), bx + bw - dp(12f))
            c.save()
            c.scale(0.8f + 0.2f * pop, 0.8f + 0.2f * pop, tailX, by + bh)
            bg.color = t.surface; bg.alpha = (255 * fade * pop.coerceAtMost(1f)).toInt()
            r.set(bx, by, bx + bw, by + bh)
            c.drawRoundRect(r, dp(12f), dp(12f), bg)
            val path = Path().apply { moveTo(tailX - dp(5f), r.bottom - 0.5f); lineTo(tailX, r.bottom + dp(6f)); lineTo(tailX + dp(5f), r.bottom - 0.5f); close() }
            c.drawPath(path, bg)
            tp.alpha = (255 * fade).toInt()
            c.translate(bx + dp(10f), by + dp(6f)); lay.draw(c)
            c.restore()
            tp.alpha = 255
        }
        // alive: drawn at a steady 30 a second while the page is open (60 while something moves)
        val moving = act != null || appear.running || pressT.running || posX.running || posY.running || abs(lean) > 0.3f
        if (moving) postInvalidateOnAnimation() else invalidateIn(33L)
    }
    private var turned = false

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
                } else quietAgain(8000)
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
        const val THREADS_BEFORE_JOKES = 3
    }
}
