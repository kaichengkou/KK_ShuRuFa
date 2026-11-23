package com.kk.shurufa.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Line icons drawn on a 24×24 grid, scaled to the key. */
object Icons {
    private fun p(block: Path.() -> Unit) = Path().apply(block)

    private val backspace = p {
        moveTo(9f, 5f); lineTo(19f, 5f); quadTo(21f, 5f, 21f, 7f); lineTo(21f, 17f); quadTo(21f, 19f, 19f, 19f)
        lineTo(9f, 19f); lineTo(3f, 12f); close()
        moveTo(12f, 9.5f); lineTo(17f, 14.5f); moveTo(17f, 9.5f); lineTo(12f, 14.5f)
    }
    private val enter = p { moveTo(19f, 6f); lineTo(19f, 10f); quadTo(19f, 14f, 15f, 14f); lineTo(5f, 14f); moveTo(9f, 10f); lineTo(5f, 14f); lineTo(9f, 18f) }
    private val shift = p { moveTo(12f, 4f); lineTo(19.5f, 11.5f); lineTo(15f, 11.5f); lineTo(15f, 19f); lineTo(9f, 19f); lineTo(9f, 11.5f); lineTo(4.5f, 11.5f); close() }
    private val globe = p {
        addCircle(12f, 12f, 8.5f, Path.Direction.CW)
        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
        addOval(RectF(8.6f, 3.5f, 15.4f, 20.5f), Path.Direction.CW)
    }
    private val left = p { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) }
    private val right = p { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }
    private val next = p { moveTo(4f, 12f); lineTo(16f, 12f); moveTo(12f, 8f); lineTo(16f, 12f); lineTo(12f, 16f); moveTo(20f, 6f); lineTo(20f, 18f) }
    private val mic = p {
        addRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, Path.Direction.CW)
        moveTo(5.5f, 11f); quadTo(5.5f, 17.5f, 12f, 17.5f); quadTo(18.5f, 17.5f, 18.5f, 11f)
        moveTo(12f, 17.5f); lineTo(12f, 21f); moveTo(9f, 21f); lineTo(15f, 21f)
    }
    private val spark = p {
        moveTo(10f, 3f); lineTo(11.8f, 8.2f); lineTo(17f, 10f); lineTo(11.8f, 11.8f); lineTo(10f, 17f)
        lineTo(8.2f, 11.8f); lineTo(3f, 10f); lineTo(8.2f, 8.2f); close()
        moveTo(18f, 13.5f); lineTo(18.9f, 15.6f); lineTo(21f, 16.5f); lineTo(18.9f, 17.4f); lineTo(18f, 19.5f)
        lineTo(17.1f, 17.4f); lineTo(15f, 16.5f); lineTo(17.1f, 15.6f); close()
    }
    /** 小K: a shark swimming right, in the same line as the other icons. */
    private val shark = p {
        moveTo(21f, 12.6f); quadTo(19.2f, 9f, 14.2f, 8.7f)
        lineTo(12.6f, 4.6f); lineTo(10.6f, 8.9f)
        quadTo(6.8f, 9.6f, 4.8f, 11.6f)
        lineTo(2.4f, 8.6f); lineTo(3.4f, 12.7f); lineTo(2.4f, 16.6f); lineTo(4.9f, 13.9f)
        quadTo(9.2f, 16.6f, 14.2f, 16.3f); quadTo(19.2f, 15.9f, 21f, 12.6f); close()
        moveTo(11.2f, 16.1f); lineTo(10.2f, 18.9f); lineTo(13.4f, 16.3f)
        moveTo(18.1f, 14.1f); quadTo(19.1f, 14.6f, 20f, 14.1f)
        // the eye: a ring this small under a 1.7 stroke is a dot
        addCircle(17.2f, 11.6f, 0.35f, Path.Direction.CW)
    }
    private val smile = p {
        addCircle(12f, 12f, 8.5f, Path.Direction.CW)
        moveTo(8.2f, 13.6f); cubicTo(9.6f, 16.4f, 14.4f, 16.4f, 15.8f, 13.6f)
        moveTo(9.3f, 9.6f); lineTo(9.3f, 10.2f); moveTo(14.7f, 9.6f); lineTo(14.7f, 10.2f)
    }
    // ---- parts of icons whose pieces move on their own when tapped ----
    private val smileFace = p {
        addCircle(12f, 12f, 8.5f, Path.Direction.CW)
    }
    private val translateBack = p { moveTo(10f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 15f) }
    private val translateFront = p {
        addRoundRect(RectF(3f, 8f, 14f, 20f), 2f, 2f, Path.Direction.CW)
        moveTo(5.8f, 17.5f); lineTo(8.5f, 11f); lineTo(11.2f, 17.5f); moveTo(6.8f, 15.3f); lineTo(10.2f, 15.3f)
    }
    private val clipBoard = p { addRoundRect(RectF(5f, 4.5f, 19f, 20.5f), 2.5f, 2.5f, Path.Direction.CW) }
    private val clipTop = p { addRect(RectF(9f, 3f, 15f, 6.5f), Path.Direction.CW) }
    private val beam = p { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(9.5f, 5f); lineTo(14.5f, 5f); moveTo(9.5f, 19f); lineTo(14.5f, 19f) }
    private val arrowL = p { moveTo(6f, 9f); lineTo(3f, 12f); lineTo(6f, 15f) }
    private val arrowR = p { moveTo(18f, 9f); lineTo(21f, 12f); lineTo(18f, 15f) }
    private val micWaveL = p { moveTo(3.5f, 8f); quadTo(2f, 11f, 3.5f, 14f) }
    private val micWaveR = p { moveTo(20.5f, 8f); quadTo(22f, 11f, 20.5f, 14f) }
    private val sparkBig = p {
        moveTo(10f, 3f); lineTo(11.8f, 8.2f); lineTo(17f, 10f); lineTo(11.8f, 11.8f); lineTo(10f, 17f)
        lineTo(8.2f, 11.8f); lineTo(3f, 10f); lineTo(8.2f, 8.2f); close()
    }
    private val sparkSmall = p {
        moveTo(18f, 13.5f); lineTo(18.9f, 15.6f); lineTo(21f, 16.5f); lineTo(18.9f, 17.4f); lineTo(18f, 19.5f)
        lineTo(17.1f, 17.4f); lineTo(15f, 16.5f); lineTo(17.1f, 15.6f); close()
    }

    private val translate = p {
        addRoundRect(RectF(3f, 8f, 14f, 20f), 2f, 2f, Path.Direction.CW)
        moveTo(10f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 15f)
        moveTo(5.8f, 17.5f); lineTo(8.5f, 11f); lineTo(11.2f, 17.5f); moveTo(6.8f, 15.3f); lineTo(10.2f, 15.3f)
    }
    private val cursor = p {
        moveTo(12f, 5f); lineTo(12f, 19f); moveTo(9.5f, 5f); lineTo(14.5f, 5f); moveTo(9.5f, 19f); lineTo(14.5f, 19f)
        moveTo(6f, 9f); lineTo(3f, 12f); lineTo(6f, 15f); moveTo(18f, 9f); lineTo(21f, 12f); lineTo(18f, 15f)
    }
    private val clip = p { addRoundRect(RectF(5f, 4.5f, 19f, 20.5f), 2.5f, 2.5f, Path.Direction.CW); addRect(RectF(9f, 3f, 15f, 6.5f), Path.Direction.CW) }
    // "tune" sliders: reads as settings without a fiddly gear outline
    private val gear = p {
        moveTo(4f, 7f); lineTo(13f, 7f); moveTo(17f, 7f); lineTo(20f, 7f); addCircle(15f, 7f, 2f, Path.Direction.CW)
        moveTo(4f, 12f); lineTo(6f, 12f); moveTo(10f, 12f); lineTo(20f, 12f); addCircle(8f, 12f, 2f, Path.Direction.CW)
        moveTo(4f, 17f); lineTo(11f, 17f); moveTo(15f, 17f); lineTo(20f, 17f); addCircle(13f, 17f, 2f, Path.Direction.CW)
    }
    private val chevDown = p { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    private val chevUp = p { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    private val lock = p { addRoundRect(RectF(5f, 11f, 19f, 20f), 2f, 2f, Path.Direction.CW); moveTo(8f, 11f); lineTo(8f, 8f); quadTo(8f, 4f, 12f, 4f); quadTo(16f, 4f, 16f, 8f); lineTo(16f, 11f) }
    private val unlock = p { addRoundRect(RectF(5f, 11f, 19f, 20f), 2f, 2f, Path.Direction.CW); moveTo(8f, 11f); lineTo(8f, 8f); quadTo(8f, 4f, 12f, 4f); quadTo(15f, 4f, 15.8f, 6.5f) }

    private val quick = p {
        addRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 2f, 2f, Path.Direction.CW)
        addRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 2f, 2f, Path.Direction.CW)
        addRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 2f, 2f, Path.Direction.CW)
        addCircle(16.75f, 16.75f, 3.25f, Path.Direction.CW)
    }
    private val close = p { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) }
    private val up = p { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(6.5f, 10.5f); lineTo(12f, 5f); lineTo(17.5f, 10.5f) }
    private val down = p { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(6.5f, 13.5f); lineTo(12f, 19f); lineTo(17.5f, 13.5f) }
    private val pin = p { moveTo(9f, 4f); lineTo(15f, 4f); moveTo(10f, 4f); lineTo(10f, 10f); lineTo(7f, 14f); lineTo(17f, 14f); lineTo(14f, 10f); lineTo(14f, 4f); moveTo(12f, 14f); lineTo(12f, 20f) }

    // cursor panel actions
    private val selectAll = p {
        // dashed frame with a cursor inside
        val segs = listOf(4f to 4f, 9f to 4f, 15f to 4f, 4f to 20f, 9f to 20f, 15f to 20f)
        segs.forEach { (x, y) -> moveTo(x, y); lineTo(x + 3.5f, y) }
        listOf(4f, 9.5f, 15f).forEach { y -> moveTo(4f, y); lineTo(4f, y + 3f); moveTo(20f, y); lineTo(20f, y + 3f) }
        moveTo(12f, 8f); lineTo(12f, 16f)
    }
    private val copy = p {
        addRoundRect(RectF(8.5f, 8.5f, 19.5f, 19.5f), 2f, 2f, Path.Direction.CW)
        moveTo(15.5f, 8.5f); lineTo(15.5f, 6.5f); quadTo(15.5f, 4.5f, 13.5f, 4.5f); lineTo(6.5f, 4.5f); quadTo(4.5f, 4.5f, 4.5f, 6.5f)
        lineTo(4.5f, 13.5f); quadTo(4.5f, 15.5f, 6.5f, 15.5f); lineTo(8.5f, 15.5f)
    }
    private val paste = p {
        addRoundRect(RectF(5f, 5f, 19f, 20.5f), 2.5f, 2.5f, Path.Direction.CW)
        addRoundRect(RectF(9f, 3f, 15f, 6.5f), 1.2f, 1.2f, Path.Direction.CW)
        moveTo(8.5f, 11f); lineTo(15.5f, 11f); moveTo(8.5f, 15f); lineTo(13f, 15f)
    }
    private val cut = p {
        addCircle(7f, 17f, 2.8f, Path.Direction.CW); addCircle(17f, 17f, 2.8f, Path.Direction.CW)
        moveTo(9f, 15f); lineTo(17.5f, 4f); moveTo(15f, 15f); lineTo(6.5f, 4f)
    }
    private val undo = p {
        moveTo(9f, 6f); lineTo(5f, 10f); lineTo(9f, 14f)
        moveTo(5f, 10f); lineTo(14f, 10f); quadTo(19.5f, 10f, 19.5f, 15f); quadTo(19.5f, 19.5f, 14f, 19.5f); lineTo(10f, 19.5f)
    }
    private val lineStart = p { moveTo(5f, 5f); lineTo(5f, 19f); moveTo(19f, 12f); lineTo(8.5f, 12f); moveTo(12.5f, 8f); lineTo(8.5f, 12f); lineTo(12.5f, 16f) }
    private val lineEnd = p { moveTo(19f, 5f); lineTo(19f, 19f); moveTo(5f, 12f); lineTo(15.5f, 12f); moveTo(11.5f, 8f); lineTo(15.5f, 12f); lineTo(11.5f, 16f) }
    private val keyboardIcon = p {
        addRoundRect(RectF(3f, 6f, 21f, 18f), 2.5f, 2.5f, Path.Direction.CW)
        listOf(7f, 10.5f, 14f, 17.5f).forEach { x -> moveTo(x, 10f); lineTo(x + 0.01f, 10f) }
        moveTo(8f, 14.5f); lineTo(16f, 14.5f)
    }

    private val check = p { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19.5f, 7f) }
    /** The small chevron after a chip that opens a list. */
    private val chevron = p { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) }
    private val search = p { addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW); moveTo(15.4f, 15.4f); lineTo(20f, 20f) }
    private val swap = p {
        moveTo(4f, 8.5f); lineTo(19f, 8.5f); moveTo(15f, 4.5f); lineTo(19f, 8.5f); lineTo(15f, 12.5f)
        moveTo(20f, 15.5f); lineTo(5f, 15.5f); moveTo(9f, 11.5f); lineTo(5f, 15.5f); lineTo(9f, 19.5f)
    }
    private val stop = p { addRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.5f, 2.5f, Path.Direction.CW) }
    private val moon = p { moveTo(19f, 14.5f); quadTo(12.5f, 17f, 9f, 12.5f); quadTo(6.5f, 8.5f, 9.5f, 4.5f); quadTo(3.5f, 6.5f, 4f, 13f); quadTo(5f, 20f, 12f, 20f); quadTo(17f, 20f, 19f, 14.5f); close() }
    private val vibrate = p {
        addRoundRect(RectF(8f, 4f, 16f, 20f), 2f, 2f, Path.Direction.CW)
        moveTo(4.5f, 8f); lineTo(4.5f, 16f); moveTo(19.5f, 8f); lineTo(19.5f, 16f)
        moveTo(2f, 10f); lineTo(2f, 14f); moveTo(22f, 10f); lineTo(22f, 14f)
    }
    private val speaker = p {
        moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f); moveTo(18f, 6.5f); quadTo(22f, 12f, 18f, 17.5f)
    }
    private val grid = p {
        addRoundRect(RectF(4f, 4f, 10.5f, 10.5f), 2f, 2f, Path.Direction.CW); addRoundRect(RectF(13.5f, 4f, 20f, 10.5f), 2f, 2f, Path.Direction.CW)
        addRoundRect(RectF(4f, 13.5f, 10.5f, 20f), 2f, 2f, Path.Direction.CW); addRoundRect(RectF(13.5f, 13.5f, 20f, 20f), 2f, 2f, Path.Direction.CW)
    }

    val all = mapOf(
        "voice" to mic, "check" to check, "chevron" to chevron, "search" to search, "swap" to swap, "stop" to stop, "moon" to moon, "vibrate" to vibrate, "speaker" to speaker, "grid" to grid,
        "select_all" to selectAll, "copy" to copy, "paste" to paste, "cut" to cut, "undo" to undo,
        "home" to lineStart, "end" to lineEnd, "abc" to keyboardIcon,
        "quick" to quick, "close" to close, "up" to up, "down" to down, "pin" to pin,
        "backspace" to backspace, "enter" to enter, "shift" to shift, "language" to globe,
        "left" to left, "right" to right, "next_slot" to next, "mic" to mic, "ai" to spark, "shark" to shark,
        "emoji" to smile, "translate" to translate, "cursor" to cursor, "clipboard" to clip,
        "settings" to gear, "collapse" to chevDown, "expand" to chevUp, "lock" to lock, "unlock" to unlock,
    )

    /**
     * Draws icon [name] centered at (cx, cy) with the given size in pixels. The canvas is scaled
     * instead of the path, so the GPU's path cache keeps every icon from frame to frame.
     */
    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, paint: Paint, filled: Boolean = false, t: Float = 1f): Boolean {
        val src = all[name] ?: return false
        val s = size / 24f
        val oldStyle = paint.style
        val oldWidth = paint.strokeWidth
        paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val save = c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(s, s)
        if (t >= 1f || t < 0f || !tapMotion(c, name, src, paint, t)) c.drawPath(src, paint)
        c.restoreToCount(save)
        paint.style = oldStyle
        paint.strokeWidth = oldWidth
        return true
    }

    // ---- tap motions: [t] runs 0→1 once after a tap; every motion ends where the icon rests ----

    /** Up and back down: 0 → 1 → 0. */
    private fun bump(t: Float) = sin(PI.toFloat() * t.coerceIn(0f, 1f))
    /** A shake that dies away: [n] swings. */
    private fun shake(t: Float, n: Int) = sin(t * PI.toFloat() * 2 * n) * (1f - t) * (1f - t)
    /** A quick rise and a springy settle back. */
    private fun hop(t: Float): Float = if (t < 0.3f) sin(t / 0.3f * PI.toFloat() / 2) else cos((t - 0.3f) / 0.7f * PI.toFloat() * 1.5f) * (1f - (t - 0.3f) / 0.7f)

    /** Draws [name] mid-motion (canvas already in the 24-unit grid). False: no motion of its own. */
    private fun tapMotion(c: Canvas, name: String, src: Path, paint: Paint, t: Float): Boolean {
        fun around(deg: Float = 0f, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f, sx: Float = scale, path: Path = src) {
            val k = c.save()
            c.translate(12f + dx, 12f + dy); c.rotate(deg); c.scale(sx, scale); c.translate(-12f, -12f)
            c.drawPath(path, paint)
            c.restoreToCount(k)
        }
        when (name) {
            // 表情: a blink, and the smile grows
            "emoji" -> {
                c.drawPath(smileFace, paint)
                val b = bump(((t - 0.08f) / 0.42f))
                val k = c.save()
                c.translate(12f, 14.6f); c.scale(1f + 0.18f * bump(t), 1f + 0.25f * bump(t)); c.translate(-12f, -14.6f)
                tmp.reset(); tmp.moveTo(8.2f, 13.6f); tmp.cubicTo(9.6f, 16.4f, 14.4f, 16.4f, 15.8f, 13.6f)
                c.drawPath(tmp, paint)
                c.restoreToCount(k)
                for (x in floatArrayOf(9.3f, 14.7f)) {
                    tmp.reset()
                    if (b > 0.6f) { tmp.moveTo(x - 1f, 10f); tmp.quadTo(x, 10.8f, x + 1f, 10f) }   // shut: a little arc
                    else { val h = 0.3f * (1f - b / 0.6f); tmp.moveTo(x, 9.9f - h); tmp.lineTo(x, 9.9f + h) }
                    c.drawPath(tmp, paint)
                }
            }
            // 翻译: the card with the letter shakes in front of the page behind it
            "translate" -> {
                c.drawPath(translateBack, paint)
                around(deg = 9f * shake(t, 3), dx = 1.4f * shake(t, 3), path = translateFront)
            }
            // 剪贴板: the clip lifts and drops back
            "clipboard" -> {
                around(scale = 1f - 0.04f * bump(t), path = clipBoard)
                around(dy = -2.6f * hop(t), path = clipTop)
            }
            // 文字编辑: the arrows push out and come back
            "cursor" -> {
                c.drawPath(beam, paint)
                val d = 2.2f * hop(t)
                around(dx = -d, path = arrowL); around(dx = d, path = arrowR)
            }
            // 小K: a little swim forward and back, the tail swishing
            "shark" -> {
                around(dx = 1.8f * shake(t, 1), deg = 6f * shake(t, 3))
                return true
            }
            // AI: the spark twists and twinkles
            "ai" -> {
                val k = c.save()
                c.translate(10f, 10f); c.rotate(45f * bump(t)); c.scale(1f + 0.12f * bump(t), 1f + 0.12f * bump(t)); c.translate(-10f, -10f)
                c.drawPath(sparkBig, paint)
                c.restoreToCount(k)
                val k2 = c.save()
                val tw = 1f + 0.6f * bump((t - 0.25f) / 0.5f)
                c.translate(18f, 16.5f); c.scale(tw, tw); c.translate(-18f, -16.5f)
                c.drawPath(sparkSmall, paint)
                c.restoreToCount(k2)
            }
            // 语音: the mic bounces, a sound ring on each side
            "mic", "voice" -> {
                around(dy = -1.6f * hop(t), scale = 1f + 0.05f * bump(t))
                val a = paint.alpha
                val w = bump(t)
                paint.alpha = (a * w).toInt()
                around(dx = -1.5f * t, path = micWaveL); around(dx = 1.5f * t, path = micWaveR)
                paint.alpha = a
            }
            // the grid of the menu turns a little and settles
            "quick", "grid" -> around(deg = 22f * shake(t, 1) + 0f, scale = 1f + 0.1f * bump(t))
            // keys: 删除 nudges left, 回车 pushes, Shift hops, the globe spins
            "backspace" -> around(dx = -2.4f * hop(t))
            "enter" -> around(dx = -2.2f * hop(t), dy = 1f * hop(t))
            "shift" -> around(dy = -2.6f * hop(t))
            "language" -> around(sx = cos(2f * PI.toFloat() * Motion.ENTER.getInterpolation(t)), scale = 1f)
            "undo" -> around(deg = -40f * bump(t))
            "settings" -> around(deg = 12f * shake(t, 2))
            else -> around(scale = 1f + 0.16f * bump(t))
        }
        return true
    }

    private val tmp = Path()
}
