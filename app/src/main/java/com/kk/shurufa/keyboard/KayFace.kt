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
 * 小K, drawn. It blinks every few seconds at irregular times, now and then looks aside, squishes
 * when a key is pressed, narrows its eyes when typing is fast, frets at a run of deletes, smiles
 * when patted, and dozes when nothing has happened for a while. Each state is a few frames; in
 * between nothing is redrawn.
 *
 * It comes in [STYLES]: 软糖 (a soft round body), 点点 (only a face), 小幽灵, 键帽 (the app icon's
 * keycap), 光团 (a glowing orb) and 像素.
 */
object KayFace {
    /** Style id to name. */
    val STYLES = linkedMapOf("mochi" to "软糖", "dots" to "点点", "ghost" to "小幽灵", "keycap" to "键帽", "orb" to "光团", "pixel" to "像素")
    var style = "mochi"

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    /**
     * Draws 小K centred at ([cx], [cy]), [size] wide: features in [ink], body in [tint] ([accent]
     * for 光团). Returns how many milliseconds until the face changes (redraw then), or 0 to keep
     * animating.
     */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, ink: Int, tint: Int, blush: Int, now: Long, hour: Int,
             mood: KayLife.Mood = KayLife.mood(now, hour), accent: Int = tint, look: String = style, squishes: Boolean = true): Long {
        val wait = KayLife.plan(now)
        var animating = false
        // the squish of a key, then settling back
        val sq = (now - KayLife.squishAt).toFloat()
        val squish = if (squishes && KayLife.squishAt > 0 && sq in 0f..220f) { animating = true; sin(PI.toFloat() * sq / 220f) * 0.09f } else 0f
        val patT = (now - KayLife.patAt).toFloat()
        val hop = if (KayLife.patAt > 0 && patT in 0f..520f) { animating = true; sin(PI.toFloat() * patT / 520f) * size * 0.08f } else 0f
        val blinkT = now - KayLife.blinkAt
        val blinking = blinkT in 0..KayLife.BLINK_MS
        if (blinking) animating = true
        val glT = now - KayLife.glanceAt
        val glance = if (glT in 0..KayLife.GLANCE_MS) KayLife.glanceDir * size * 0.05f * sin(PI.toFloat() * glT / KayLife.GLANCE_MS) else 0f
        if (glT in 0..KayLife.GLANCE_MS) animating = true

        val w = size * (1f + squish * 0.7f)
        val h = size * 0.84f * (1f - squish)
        val top = cy - h / 2 - hop + squish * size * 0.12f
        rect.set(cx - w / 2, top, cx + w / 2, top + h)
        var features = ink
        var faceTop = rect.top; var faceH = h
        when (look) {
            "dots" -> {}
            "ghost" -> {
                // a rounded head, sides straight down, three soft bumps for a hem
                val r0 = w / 2
                path.reset()
                path.moveTo(rect.left, rect.top + r0)
                path.arcTo(rect.left, rect.top, rect.right, rect.top + w, 180f, 180f, false)
                path.lineTo(rect.right, rect.bottom - h * 0.12f)
                val bump = w / 3
                for (k in 0 until 3) {
                    val x0 = rect.right - k * bump
                    path.quadTo(x0 - bump / 2, rect.bottom + h * 0.06f, x0 - bump, rect.bottom - h * 0.12f)
                }
                path.close()
                body.color = tint; c.drawPath(path, body)
                faceTop = rect.top + h * 0.06f
            }
            "keycap" -> {
                // a keycap like the app's icon: its side, darker, and the top it is pressed on
                val rad = size * 0.2f
                body.color = Colors.blend(tint, ink, 0.18f)
                c.drawRoundRect(rect, rad, rad, body)
                val face = RectF(rect.left + w * 0.06f, rect.top + h * 0.03f, rect.right - w * 0.06f, rect.bottom - h * 0.16f)
                body.color = Colors.blend(tint, 0xFFFFFFFF.toInt(), 0.55f)
                c.drawRoundRect(face, rad * 0.8f, rad * 0.8f, body)
                faceTop = face.top; faceH = face.height() * 1.1f
            }
            "orb" -> {
                // a soft glow, then a bright core: something made of light
                body.shader = android.graphics.RadialGradient(cx, cy - hop, size * 0.62f,
                    intArrayOf(Colors.alpha(accent, 0x55), Colors.alpha(accent, 0)), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy - hop, size * 0.62f, body)
                body.shader = android.graphics.RadialGradient(cx - size * 0.1f, cy - hop - size * 0.12f, size * 0.5f,
                    intArrayOf(Colors.blend(accent, 0xFFFFFFFF.toInt(), 0.35f), accent), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawOval(rect, body)
                body.shader = null
                features = 0xFFFFFFFF.toInt()
            }
            "pixel" -> return drawPixel(c, cx, cy - hop, size, ink, tint, mood, blinking, if (glance > 0) 1 else if (glance < 0) -1 else 0, now, animating, wait)
            else -> {
                val rad = min(w, h) * 0.46f
                body.color = tint
                c.drawRoundRect(rect, rad, rad, body)
            }
        }

        // eyes: where they look, and whether they are open
        val ey = faceTop + faceH * 0.44f
        val ex = size * (if (look == "dots") 0.2f else 0.17f)
        val er = size * (if (look == "dots") 0.075f else 0.055f)
        fill.color = features; line.color = features
        line.strokeWidth = size * (if (look == "dots") 0.07f else 0.055f)
        for (side in intArrayOf(-1, 1)) {
            val x = cx + side * ex + glance
            when {
                mood == KayLife.Mood.HAPPY -> { path.reset(); path.moveTo(x - er * 1.4f, ey + er * 0.5f); path.quadTo(x, ey - er * 1.6f, x + er * 1.4f, ey + er * 0.5f); c.drawPath(path, line) }
                mood == KayLife.Mood.DOZE || mood == KayLife.Mood.SLEEPY && !blinking -> {
                    path.reset(); path.moveTo(x - er * 1.3f, ey - er * 0.2f); path.quadTo(x, ey + er * (if (mood == KayLife.Mood.DOZE) 1.3f else 0.6f), x + er * 1.3f, ey - er * 0.2f); c.drawPath(path, line)
                }
                blinking -> c.drawLine(x - er * 1.2f, ey, x + er * 1.2f, ey, line)
                mood == KayLife.Mood.BUSY -> {
                    path.reset(); path.moveTo(x - side * er * 1.1f, ey - er * 1.1f); path.lineTo(x + side * er * 0.9f, ey); path.lineTo(x - side * er * 1.1f, ey + er * 1.1f); c.drawPath(path, line)
                }
                else -> c.drawCircle(x, ey, er, fill)
            }
        }
        // the mouth, and what goes with the mood
        val my = faceTop + faceH * 0.68f
        val mw = size * 0.09f
        path.reset()
        when (mood) {
            KayLife.Mood.HAPPY -> {
                path.moveTo(cx - mw, my - mw * 0.2f); path.quadTo(cx, my + mw * 1.2f, cx + mw, my - mw * 0.2f)
                fill.color = blush
                c.drawOval(cx - ex - size * 0.11f, my - size * 0.06f, cx - ex + size * 0.03f, my + size * 0.02f, fill)
                c.drawOval(cx + ex - size * 0.03f, my - size * 0.06f, cx + ex + size * 0.11f, my + size * 0.02f, fill)
                fill.color = features
            }
            KayLife.Mood.WORRIED -> {
                path.moveTo(cx - mw, my + mw * 0.3f); path.quadTo(cx - mw * 0.5f, my - mw * 0.4f, cx, my + mw * 0.1f); path.quadTo(cx + mw * 0.5f, my + mw * 0.5f, cx + mw, my - mw * 0.2f)
                fill.color = blush
                c.drawCircle(rect.right - size * 0.06f, rect.top + size * 0.16f, size * 0.045f, fill)
                fill.color = features
            }
            KayLife.Mood.BUSY -> c.drawCircle(cx, my + mw * 0.2f, mw * 0.45f, fill)
            KayLife.Mood.DOZE, KayLife.Mood.SLEEPY -> { path.moveTo(cx - mw * 0.6f, my + mw * 0.2f); path.lineTo(cx + mw * 0.6f, my + mw * 0.2f) }
            KayLife.Mood.CALM -> { path.moveTo(cx - mw, my - mw * 0.1f); path.quadTo(cx, my + mw * 0.9f, cx + mw, my - mw * 0.1f) }
        }
        if (!path.isEmpty) c.drawPath(path, line)
        return finish(c, mood, ink, size, rect.right, rect.top, now, animating, wait)
    }

    /** The z of a doze, and how long until the next frame. */
    private fun finish(c: Canvas, mood: KayLife.Mood, ink: Int, size: Float, right: Float, top: Float, now: Long, animating: Boolean, wait: Long): Long {
        if (mood == KayLife.Mood.DOZE) {
            // z, rising slowly: the one thing that moves while it sleeps, a frame a second
            text.color = ink; text.textSize = size * 0.32f
            val zt = (now / 1000 % 3).toFloat()
            text.alpha = (255 * (1f - zt / 3f)).toInt()
            c.drawText("z", right + size * 0.02f, top - zt * size * 0.06f, text)
            text.alpha = 255
            return if (animating) 0L else 1000L
        }
        if (animating) return 0L
        // nothing moving: until the next blink or glance, or until it would doze off
        val doze = if (KayLife.lastKey > 0) KayLife.lastKey + 90_000 - now else Long.MAX_VALUE
        val happyEnds = if (KayLife.patAt > 0) KayLife.patAt + 2600 - now else -1
        return listOf(wait, doze, if (happyEnds > 0) happyEnds else Long.MAX_VALUE).filter { it > 0 }.minOrNull()?.coerceIn(16L, 60_000L) ?: 1000L
    }

    /** 像素: a 9 × 8 sprite, its face a few pixels. */
    private fun drawPixel(c: Canvas, cx: Float, cy: Float, size: Float, ink: Int, tint: Int, mood: KayLife.Mood, blinking: Boolean, look: Int, now: Long, animating: Boolean, wait: Long): Long {
        val cell = size / 9f
        val left = cx - cell * 4.5f; val top = cy - cell * 4f
        fun px(x: Int, y: Int, color: Int) { fill.color = color; c.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell + 0.5f, top + (y + 1) * cell + 0.5f, fill) }
        // the body: a rounded block, its corners left out
        for (y in 0 until 8) for (x in 0 until 9) {
            val corner = (y == 0 || y == 7) && (x <= 1 || x >= 7) || (y == 1 || y == 6) && (x == 0 || x == 8)
            if (!corner) px(x, y, tint)
        }
        val e = 2 + look.coerceIn(-1, 1).let { if (it < 0) 0 else it }
        val eyes = intArrayOf(e, e + 4)
        when {
            mood == KayLife.Mood.HAPPY -> for (x in eyes) { px(x - 1, 3, ink); px(x, 2, ink); px(x + 1, 3, ink) }
            mood == KayLife.Mood.DOZE || mood == KayLife.Mood.SLEEPY || blinking -> for (x in eyes) { px(x - 1, 3, ink); px(x, 3, ink) }
            mood == KayLife.Mood.BUSY -> { px(eyes[0] - 1, 2, ink); px(eyes[0], 3, ink); px(eyes[0] - 1, 4, ink); px(eyes[1] + 1, 2, ink); px(eyes[1], 3, ink); px(eyes[1] + 1, 4, ink) }
            else -> for (x in eyes) { px(x, 2, ink); px(x, 3, ink) }
        }
        when (mood) {
            KayLife.Mood.HAPPY, KayLife.Mood.CALM -> { px(3, 5, ink); px(4, 6, ink); px(5, 5, ink) }
            KayLife.Mood.WORRIED -> { px(3, 6, ink); px(4, 5, ink); px(5, 6, ink) }
            KayLife.Mood.BUSY -> px(4, 5, ink)
            else -> { px(4, 5, ink); px(5, 5, ink) }
        }
        return finish(c, mood, ink, size, left + cell * 9, top, now, animating, wait)
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
