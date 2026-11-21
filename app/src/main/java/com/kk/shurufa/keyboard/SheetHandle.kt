package com.kk.shurufa.keyboard

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import com.kk.shurufa.Prefs
import kotlin.math.abs

/**
 * Grab bar at the top of a panel. Dragging it changes the panel's height between two detents
 * (the keyboard's height and a tall one); on release a spring carries the panel to the nearer
 * detent, or to the one the fling points at, at the speed the finger left it.
 */
@SuppressLint("ViewConstructor")
class SheetHandle(context: Context, prefs: Prefs) : KbView(context, prefs) {
    var low = 0
    var high = 0
    /** Current panel height; set by the owner, changed live while dragging. */
    var current = 0
    var onHeight: ((Int) -> Unit)? = null
    var onSettled: ((tall: Boolean) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null
    /** After a drag, once the sheet has come to rest at a detent. */
    var onRest: (() -> Unit)? = null
    /** Being dragged or still settling: the app below should not be resized meanwhile. */
    val moving get() = active || ticker.isStarted

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downY = 0f
    private var startH = 0
    private var active = false
    private var crossed = false
    private var vt: VelocityTracker? = null
    private val grip = Tween(0f)
    private val spring = Spring(0f, stiffness = 720f, dampingRatio = 0.8f)
    private val ticker = TimeAnimator().apply {
        setTimeListener { a, _, _ ->
            val h = spring.step()
            current = h.toInt()
            onHeight?.invoke(current)
            if (!spring.running) { a.end(); onRest?.invoke() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(18f).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val g = grip.value
        if (grip.running) postInvalidateOnAnimation()
        // the grip widens and takes the accent while it is held
        paint.color = Colors.blend(Colors.alpha(theme.hint, 150), theme.accent, g)
        val w = dp(36f) + dp(12f) * g
        val h = dp(4f)
        c.drawRoundRect(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2, h / 2, h / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e.rawY)
            MotionEvent.ACTION_MOVE -> drag(e.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                end(vt?.yVelocity ?: 0f)
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    /** Drags can also start elsewhere (the toolbar above the panel); they go through these. */
    fun begin(rawY: Float) {
        ticker.cancel()
        downY = rawY; startH = current; active = true; crossed = startH > (low + high) / 2
        grip.animateTo(1f, dur(Motion.PRESS + 60))
        onFeedback?.invoke("press")
        invalidate()
    }

    fun drag(rawY: Float) {
        val raw = startH - (rawY - downY)
        // beyond the detents the sheet stretches only a little, and less the further it goes
        val h = when {
            raw > high -> high + rubber(raw - high)
            raw < low -> low - rubber(low - raw)
            else -> raw
        }.toInt()
        val nowCrossed = h > (low + high) / 2
        if (nowCrossed != crossed) { crossed = nowCrossed; onFeedback?.invoke("press") }
        current = h
        onHeight?.invoke(h)
    }

    private fun rubber(over: Float): Float { val lim = dp(48f); return lim * (1f - 1f / (over / lim * 0.6f + 1f)) }

    fun end(velocityY: Float) {
        val tall = when {
            abs(velocityY) > dp(600f) -> velocityY < 0
            else -> current > (low + high) / 2
        }
        settle(tall, -velocityY)
        active = false
        grip.animateTo(0f, dur(Motion.RELEASE))
        invalidate()
    }

    /** Carries the sheet to a detent; [velocity] is the height's speed in px/s when the finger let go. */
    fun settle(tall: Boolean, velocity: Float = 0f) {
        val target = (if (tall) high else low).toFloat()
        ticker.cancel()
        spring.snap(current.toFloat())
        spring.enabled = Motion.on(prefs)
        spring.animateTo(target, velocity.coerceIn(-dp(4000f), dp(4000f)))
        if (spring.running) ticker.start() else { current = target.toInt(); onHeight?.invoke(current); onRest?.invoke() }
        onFeedback?.invoke("gesture")
        onSettled?.invoke(tall)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (ticker.isStarted) { ticker.end(); onRest?.invoke() }
    }
}
