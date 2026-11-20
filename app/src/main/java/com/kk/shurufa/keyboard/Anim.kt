package com.kk.shurufa.keyboard

import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/** A value that moves smoothly toward a target. Read [value] every frame; [running] says whether to keep drawing. */
class Tween(initial: Float = 0f) {
    private var from = initial
    private var to = initial
    private var start = 0L
    private var dur = 0L
    private var interp: Interpolator = EASE_OUT

    val target get() = to

    fun set(v: Float) { from = v; to = v; dur = 0 }

    fun animateTo(v: Float, durationMs: Long, interpolator: Interpolator = EASE_OUT) {
        if (durationMs <= 0) { set(v); return }
        from = value
        to = v
        start = SystemClock.uptimeMillis()
        dur = durationMs
        interp = interpolator
    }

    val value: Float
        get() {
            if (dur == 0L) return to
            val t = ((SystemClock.uptimeMillis() - start).toFloat() / dur).coerceIn(0f, 1f)
            if (t >= 1f) { dur = 0; from = to; return to }
            return from + (to - from) * interp.getInterpolation(t)
        }

    val running get() = dur != 0L && SystemClock.uptimeMillis() - start < dur

    companion object {
        val EASE_OUT: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EASE_IN_OUT: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val POP: Interpolator = OvershootInterpolator(1.6f)
        val DECEL: Interpolator = DecelerateInterpolator(1.8f)
    }
}
