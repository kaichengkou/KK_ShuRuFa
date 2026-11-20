package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Round direction pad for the cursor panel. Holding an arrow repeats it; the center toggles
 * selection mode (moving then extends the selection).
 */
@SuppressLint("ViewConstructor")
class DpadView(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onArrow: ((Dir) -> Unit)? = null
    var onCenter: (() -> Unit)? = null
    var selecting = false
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Dir? = null
    private var centerPressed = false
    private val press = Tween(0f)

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val radius get() = min(width, height) / 2f - dp(8f)
    private val inner get() = radius * 0.38f

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val r = radius
        paint.color = theme.soft
        c.drawCircle(cx, cy, r, paint)
        val p = press.value
        if (press.running) postInvalidateOnAnimation()
        pressed?.let { d ->
            paint.color = theme.accentSoft
            paint.alpha = (255 * p).coerceAtLeast(60f).toInt()
            val start = when (d) { Dir.RIGHT -> -45f; Dir.DOWN -> 45f; Dir.LEFT -> 135f; Dir.UP -> 225f }
            val path = Path().apply {
                arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, 90f, true)
                arcTo(RectF(cx - inner, cy - inner, cx + inner, cy + inner), start + 90f, -90f)
                close()
            }
            c.drawPath(path, paint)
        }
        // hairlines between the four directions
        paint.color = theme.divider
        paint.strokeWidth = dp(1f)
        for (k in 0 until 4) {
            val ang = Math.toRadians(45.0 + 90 * k)
            val cos = Math.cos(ang).toFloat(); val sin = Math.sin(ang).toFloat()
            c.drawLine(cx + cos * (inner + dp(4f)), cy + sin * (inner + dp(4f)), cx + cos * (r - dp(6f)), cy + sin * (r - dp(6f)), paint)
        }
        val ip = Paint(Paint.ANTI_ALIAS_FLAG)
        val mid = (r + inner) / 2
        listOf(Dir.UP to "up", Dir.DOWN to "down", Dir.LEFT to "left", Dir.RIGHT to "right").forEach { (d, icon) ->
            ip.color = if (pressed == d) theme.accent else theme.inkSoft
            val (x, y) = when (d) { Dir.UP -> cx to cy - mid; Dir.DOWN -> cx to cy + mid; Dir.LEFT -> cx - mid to cy; Dir.RIGHT -> cx + mid to cy }
            val name = when (d) { Dir.LEFT -> "left"; Dir.RIGHT -> "right"; else -> icon }
            Icons.draw(c, name, x, y, dp(24f), ip)
        }
        // center: selection toggle
        paint.color = when {
            selecting -> theme.accent
            centerPressed -> theme.surfacePressed
            else -> theme.surface
        }
        c.drawCircle(cx, cy, inner - dp(2f), paint)
        tp.color = if (selecting) theme.onAccent else theme.ink
        tp.textSize = sp(14f)
        tp.isFakeBoldText = selecting
        val fm = tp.fontMetrics
        c.drawText(if (selecting) "选择中" else "选择", cx, cy - (fm.ascent + fm.descent) / 2, tp)
    }

    private val repeat = object : Runnable {
        override fun run() {
            pressed?.let { onArrow?.invoke(it) }
            handler.postDelayed(this, 70)
        }
    }

    private fun dirAt(x: Float, y: Float): Dir? {
        val dx = x - cx; val dy = y - cy
        if (hypot(dx, dy) < inner) return null
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        return when {
            deg >= -45 && deg < 45 -> Dir.RIGHT
            deg >= 45 && deg < 135 -> Dir.DOWN
            deg >= -135 && deg < -45 -> Dir.UP
            else -> Dir.LEFT
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d = dirAt(e.x, e.y)
                if (d == null) centerPressed = true
                else {
                    pressed = d
                    press.set(0f); press.animateTo(1f, 90)
                    onArrow?.invoke(d)
                    handler.postDelayed(repeat, 380)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressed != null) {
                val d = dirAt(e.x, e.y)
                if (d != null && d != pressed) { pressed = d; onArrow?.invoke(d); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeat)
                if (centerPressed && e.actionMasked == MotionEvent.ACTION_UP && dirAt(e.x, e.y) == null) onCenter?.invoke()
                centerPressed = false
                pressed = null
                invalidate()
            }
        }
        return true
    }
}
