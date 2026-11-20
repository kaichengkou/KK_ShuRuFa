package com.kk.shurufa.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

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
    private val smile = p {
        addCircle(12f, 12f, 8.5f, Path.Direction.CW)
        moveTo(8.2f, 13.6f); cubicTo(9.6f, 16.4f, 14.4f, 16.4f, 15.8f, 13.6f)
        moveTo(9.3f, 9.6f); lineTo(9.3f, 10.2f); moveTo(14.7f, 9.6f); lineTo(14.7f, 10.2f)
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

    val all = mapOf(
        "select_all" to selectAll, "copy" to copy, "paste" to paste, "cut" to cut, "undo" to undo,
        "home" to lineStart, "end" to lineEnd, "abc" to keyboardIcon,
        "quick" to quick, "close" to close, "up" to up, "down" to down, "pin" to pin,
        "backspace" to backspace, "enter" to enter, "shift" to shift, "language" to globe,
        "left" to left, "right" to right, "next_slot" to next, "mic" to mic, "ai" to spark,
        "emoji" to smile, "translate" to translate, "cursor" to cursor, "clipboard" to clip,
        "settings" to gear, "collapse" to chevDown, "expand" to chevUp, "lock" to lock, "unlock" to unlock,
    )

    /**
     * Draws icon [name] centered at (cx, cy) with the given size in pixels. The canvas is scaled
     * instead of the path, so the GPU's path cache keeps every icon from frame to frame.
     */
    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, paint: Paint, filled: Boolean = false): Boolean {
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
        c.drawPath(src, paint)
        c.restoreToCount(save)
        paint.style = oldStyle
        paint.strokeWidth = oldWidth
        return true
    }
}
