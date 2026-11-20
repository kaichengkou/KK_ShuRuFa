package com.kk.shurufa.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.OvershootInterpolator
import com.kk.shurufa.Prefs
import kotlin.math.abs

/**
 * Grab bar at the top of a panel. Dragging it changes the panel's height between two detents
 * (the keyboard's height and a tall one); on release it snaps to the nearer detent, or to the
 * one the fling points at, with a slight overshoot.
 */
@SuppressLint("ViewConstructor")
class SheetHandle(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var low = 0
    var high = 0
    /** Current panel height; set by the owner, changed live while dragging. */
    var current = 0
    var onHeight: ((Int) -> Unit)? = null
    var onSettled: ((tall: Boolean) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downY = 0f
    private var startH = 0
    private var active = false
    private var crossed = false
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(18f).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        paint.color = if (active) theme.accent else theme.hint
        paint.alpha = if (active) 255 else 150
        val w = if (active) dp(46f) else dp(36f)
        val h = dp(4f)
        c.drawRoundRect(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2, h / 2, h / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downY = e.rawY; startH = current; active = true; crossed = startH > (low + high) / 2
                onFeedback?.invoke("press")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val raw = startH - (e.rawY - downY)
                // beyond the detents the sheet stretches only a little
                val h = when {
                    raw > high -> high + (raw - high) * 0.25f
                    raw < low -> low - (low - raw) * 0.25f
                    else -> raw
                }.toInt()
                val nowCrossed = h > (low + high) / 2
                if (nowCrossed != crossed) { crossed = nowCrossed; onFeedback?.invoke("press") }
                current = h
                onHeight?.invoke(h)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                val tall = when {
                    abs(v) > dp(600f) -> v < 0
                    else -> current > (low + high) / 2
                }
                settle(tall)
                vt?.recycle(); vt = null
                active = false
                invalidate()
            }
        }
        return true
    }

    fun settle(tall: Boolean) {
        val target = if (tall) high else low
        anim?.cancel()
        anim = ValueAnimator.ofInt(current, target).apply {
            duration = if (prefs.animations) (300 * prefs.animSpeed).toLong() else 0L
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener { current = it.animatedValue as Int; onHeight?.invoke(current) }
            start()
        }
        onFeedback?.invoke("gesture")
        onSettled?.invoke(tall)
    }
}
