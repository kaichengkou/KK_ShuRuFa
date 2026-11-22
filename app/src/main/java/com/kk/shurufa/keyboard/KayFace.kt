package com.kk.shurufa.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 小K's life, as far as the keyboard can feel it: when keys were last pressed and how fast, the
 * deletes, a pat. Nothing here is stored; it is the moment, and the face reads it.
 */
object KayLife {
    enum class Mood { CALM, BUSY, WORRIED, HAPPY, SLEEPY, DOZE }

    private val keys = LongArray(24)
    private var keyAt = 0
    private val dels = LongArray(12)
    private var delAt = 0
    @Volatile var lastKey = 0L; private set
    @Volatile var patAt = 0L; private set
    /** The last key's squish starts here. */
    @Volatile var squishAt = 0L; private set

    fun key(now: Long) { keys[keyAt++ % keys.size] = now; lastKey = now; squishAt = now }
    fun delete(now: Long) { dels[delAt++ % dels.size] = now; key(now) }
    fun pat(now: Long) { patAt = now; lastKey = now }

    private fun within(a: LongArray, now: Long, ms: Long) = a.count { it > 0 && now - it < ms }

    /** How 小K is now; [hour] of the day (0–23). */
    fun mood(now: Long, hour: Int): Mood = when {
        patAt > 0 && now - patAt < 2600 -> Mood.HAPPY
        within(dels, now, 5000) >= 6 -> Mood.WORRIED
        within(keys, now, 5000) >= 14 -> Mood.BUSY
        lastKey > 0 && now - lastKey > 90_000 -> Mood.DOZE
        hour in 0..4 -> Mood.SLEEPY
        else -> Mood.CALM
    }

    // blinking and glancing: irregular, like something alive, and only a few frames each
    private val rnd = java.util.Random()
    @Volatile var blinkAt = 0L; private set
    @Volatile var glanceAt = 0L; private set
    @Volatile var glanceDir = 0f; private set

    /** Plans the next blink and glance after [now]; returns how long until something changes. */
    fun plan(now: Long): Long {
        if (blinkAt + BLINK_MS < now) blinkAt = now + 2600 + rnd.nextInt(4200)
        if (glanceAt + GLANCE_MS < now) { glanceAt = now + 5000 + rnd.nextInt(9000); glanceDir = if (rnd.nextBoolean()) 1f else -1f }
        return min(blinkAt, glanceAt) - now
    }

    const val BLINK_MS = 140L
    const val GLANCE_MS = 1200L
}

/**
 * 小K, drawn: a small soft round thing with two eyes. It breathes only while something happens,
 * blinks every few seconds at irregular times, now and then looks aside, squishes when a key is
 * pressed, frowns at a run of deletes, narrows its eyes when typing is fast, smiles when patted,
 * and dozes when nothing has happened for a while. Each state is a few frames; in between nothing
 * is redrawn.
 */
object KayFace {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    /**
     * Draws 小K centred at ([cx], [cy]), [size] wide, in [ink] on a [tint] body. Returns how many
     * milliseconds until the face changes (redraw then), or 0 to keep animating.
     */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, ink: Int, tint: Int, blush: Int, now: Long, hour: Int, mood: KayLife.Mood = KayLife.mood(now, hour)): Long {
        val wait = KayLife.plan(now)
        var animating = false
        // the squish of a key, then settling back: a tiny breath
        val sq = (now - KayLife.squishAt).toFloat()
        val squish = if (KayLife.squishAt > 0 && sq in 0f..220f) { animating = true; sin(PI.toFloat() * sq / 220f) * 0.09f } else 0f
        val patT = (now - KayLife.patAt).toFloat()
        val hop = if (KayLife.patAt > 0 && patT in 0f..520f) { animating = true; sin(PI.toFloat() * patT / 520f) * size * 0.08f } else 0f
        val w = size * (1f + squish * 0.7f)
        val h = size * 0.84f * (1f - squish)
        val top = cy - h / 2 - hop + squish * size * 0.12f
        rect.set(cx - w / 2, top, cx + w / 2, top + h)
        val rad = min(w, h) * 0.46f
        body.color = tint
        c.drawRoundRect(rect, rad, rad, body)
        // a light outline: a soft thing, not a sticker
        line.color = Colors.fade(ink, 0.55f); line.strokeWidth = size * 0.045f
        c.drawRoundRect(rect, rad, rad, line)
        line.color = ink

        // eyes: where they look, and whether they are open
        val blinkT = now - KayLife.blinkAt
        val blinking = blinkT in 0..KayLife.BLINK_MS
        if (blinking) animating = true
        val glT = now - KayLife.glanceAt
        val glance = if (glT in 0..KayLife.GLANCE_MS) KayLife.glanceDir * size * 0.05f * sin(PI.toFloat() * glT / KayLife.GLANCE_MS) else 0f
        if (glT in 0..KayLife.GLANCE_MS) animating = true
        val ey = rect.top + h * 0.44f
        val ex = size * 0.17f
        val er = size * 0.055f
        fill.color = ink
        line.strokeWidth = size * 0.055f
        for (side in intArrayOf(-1, 1)) {
            val x = cx + side * ex + glance
            when {
                mood == KayLife.Mood.HAPPY -> { path.reset(); path.moveTo(x - er * 1.4f, ey + er * 0.5f); path.quadTo(x, ey - er * 1.6f, x + er * 1.4f, ey + er * 0.5f); c.drawPath(path, line) }
                mood == KayLife.Mood.DOZE || mood == KayLife.Mood.SLEEPY && !blinking -> {
                    // closed, or nearly: a soft curve downward
                    path.reset(); path.moveTo(x - er * 1.3f, ey - er * 0.2f); path.quadTo(x, ey + er * (if (mood == KayLife.Mood.DOZE) 1.3f else 0.6f), x + er * 1.3f, ey - er * 0.2f); c.drawPath(path, line)
                }
                blinking -> c.drawLine(x - er * 1.2f, ey, x + er * 1.2f, ey, line)
                mood == KayLife.Mood.BUSY -> {
                    // > <: focused
                    path.reset(); path.moveTo(x - side * er * 1.1f, ey - er * 1.1f); path.lineTo(x + side * er * 0.9f, ey); path.lineTo(x - side * er * 1.1f, ey + er * 1.1f); c.drawPath(path, line)
                }
                else -> c.drawCircle(x, ey, er, fill)
            }
        }
        // the mouth, and what goes with the mood
        val my = rect.top + h * 0.68f
        val mw = size * 0.09f
        path.reset()
        when (mood) {
            KayLife.Mood.HAPPY -> {
                path.moveTo(cx - mw, my - mw * 0.2f); path.quadTo(cx, my + mw * 1.2f, cx + mw, my - mw * 0.2f)
                fill.color = blush
                c.drawOval(cx - ex - size * 0.11f, my - size * 0.06f, cx - ex + size * 0.03f, my + size * 0.02f, fill)
                c.drawOval(cx + ex - size * 0.03f, my - size * 0.06f, cx + ex + size * 0.11f, my + size * 0.02f, fill)
                fill.color = ink
            }
            KayLife.Mood.WORRIED -> {
                path.moveTo(cx - mw, my + mw * 0.3f); path.quadTo(cx - mw * 0.5f, my - mw * 0.4f, cx, my + mw * 0.1f); path.quadTo(cx + mw * 0.5f, my + mw * 0.5f, cx + mw, my - mw * 0.2f)
                // a drop of sweat
                fill.color = blush
                val dx = rect.right - size * 0.06f; val dy = rect.top + size * 0.12f
                c.drawCircle(dx, dy + size * 0.04f, size * 0.045f, fill)
                fill.color = ink
            }
            KayLife.Mood.BUSY -> { c.drawCircle(cx, my + mw * 0.2f, mw * 0.45f, fill) }
            KayLife.Mood.DOZE, KayLife.Mood.SLEEPY -> { path.moveTo(cx - mw * 0.6f, my + mw * 0.2f); path.lineTo(cx + mw * 0.6f, my + mw * 0.2f) }
            KayLife.Mood.CALM -> { path.moveTo(cx - mw, my - mw * 0.1f); path.quadTo(cx, my + mw * 0.9f, cx + mw, my - mw * 0.1f) }
        }
        if (!path.isEmpty) c.drawPath(path, line)
        if (mood == KayLife.Mood.DOZE) {
            // z, rising slowly: the one thing that moves while it sleeps, a frame a second
            text.color = ink; text.textSize = size * 0.32f
            val zt = (now / 1000 % 3).toFloat()
            text.alpha = (255 * (1f - zt / 3f)).toInt()
            c.drawText("z", rect.right + size * 0.02f, rect.top - zt * size * 0.06f, text)
            text.alpha = 255
            return if (animating) 0L else 1000L
        }
        if (animating) return 0L
        // nothing moving: until the next blink or glance, or until it would doze off
        val doze = if (KayLife.lastKey > 0) KayLife.lastKey + 90_000 - now else Long.MAX_VALUE
        val happyEnds = if (KayLife.patAt > 0) KayLife.patAt + 2600 - now else -1
        return listOf(wait, doze, if (happyEnds > 0) happyEnds else Long.MAX_VALUE).filter { it > 0 }.minOrNull()?.coerceIn(16L, 60_000L) ?: 1000L
    }

    /** A kaomoji for the mood, for places a drawing does not fit (the space key). */
    fun kaomoji(mood: KayLife.Mood): String = when (mood) {
        KayLife.Mood.HAPPY -> "(｡>ω<｡)"
        KayLife.Mood.BUSY -> "✧(•̀ω•́)"
        KayLife.Mood.WORRIED -> "(；´д｀)"
        KayLife.Mood.SLEEPY, KayLife.Mood.DOZE -> "( ˘ω˘ )zZ"
        KayLife.Mood.CALM -> "(・ω・)"
    }
}
