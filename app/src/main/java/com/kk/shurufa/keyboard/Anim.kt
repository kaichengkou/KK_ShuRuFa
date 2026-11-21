package com.kk.shurufa.keyboard

import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One motion language for every surface. Tweens fade and slide what simply appears or swaps;
 * springs move what the finger let go of (a sheet, a highlight, a label coming home), so a
 * motion interrupted halfway carries on from where it is, at the speed it had. All of it is
 * scaled by 动画时长 and turned off with 动画.
 */
object Motion {
    /** Entering and settling: a quick start and a long, soft landing. */
    val ENTER: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Leaving: gathers speed on the way out. */
    val EXIT: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    /** From one place on screen to another. */
    val MOVE: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val LINEAR: Interpolator = LinearInterpolator()

    // Durations in ms, before the 动画时长 factor.
    /** A key going down: almost at once, so the finger never waits for it. */
    const val PRESS = 24L
    /** A key coming back up. */
    const val RELEASE = 170L
    /** Content swapping in place (candidates, labels). */
    const val SWAP = 140L
    /** A popup or bubble appearing. */
    const val POP_IN = 150L
    const val POP_OUT = 100L
    /** One panel replacing another, and the keyboard changing height with it. */
    const val PANEL = 280L

    /** [ms] scaled by the user's speed, or 0 when animations are off. */
    fun dur(prefs: Prefs, ms: Long): Long = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    /** The time every animation of one frame agrees on. */
    fun now(): Long = AnimationUtils.currentAnimationTimeMillis()
}

/** A value that moves smoothly toward a target. Read [value] every frame; [running] says whether to keep drawing. */
class Tween(initial: Float = 0f) {
    private var from = initial
    private var to = initial
    private var start = 0L
    private var dur = 0L
    private var interp: Interpolator = Motion.MOVE

    val target get() = to

    fun set(v: Float) { from = v; to = v; dur = 0 }

    fun animateTo(v: Float, durationMs: Long, interpolator: Interpolator = Motion.MOVE) {
        if (durationMs <= 0) { set(v); return }
        if (v == to && running) return
        from = value
        to = v
        start = Motion.now()
        dur = durationMs
        interp = interpolator
    }

    val value: Float
        get() {
            if (dur == 0L) return to
            val t = ((Motion.now() - start).toFloat() / dur).coerceIn(0f, 1f)
            if (t >= 1f) { dur = 0; from = to; return to }
            return from + (to - from) * interp.getInterpolation(t)
        }

    val running get() = dur != 0L && Motion.now() - start < dur
}

/**
 * A damped spring (unit mass) solved exactly for each frame, so it behaves the same at any frame
 * rate. [dampingRatio] 1 settles without overshoot; below 1 it swings past the target a little.
 * With animations off it jumps.
 */
class Spring(initial: Float = 0f, var stiffness: Float = 600f, var dampingRatio: Float = 0.85f) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var last = 0L
    private var moving = false
    /** Off: every change jumps straight to the target. */
    var enabled = true

    val running get() = moving

    /** Puts the spring at rest at [v]. */
    fun snap(v: Float) { value = v; target = v; velocity = 0f; moving = false }

    /** Sets off toward [t]; a fling passes its [startVelocity] (units per second). */
    fun animateTo(t: Float, startVelocity: Float = velocity) {
        if (!enabled) { snap(t); return }
        if (!moving) last = Motion.now()
        target = t
        velocity = startVelocity
        moving = abs(value - t) > EPS || abs(velocity) > EPS
        if (!moving) snap(t)
    }

    /** Follows the finger: the value is set directly, keeping a velocity estimate for the release. */
    fun drag(v: Float) {
        val now = Motion.now()
        val dt = (now - last).coerceAtLeast(1L) / 1000f
        velocity = if (moving || last > 0) ((v - value) / dt).coerceIn(-MAX_V, MAX_V) else 0f
        value = v; target = v; last = now; moving = false
    }

    /** Advances to this frame; returns the value. Call once per frame while [running]. */
    fun step(): Float {
        if (!moving) return value
        val now = Motion.now()
        var dt = (now - last) / 1000f
        last = now
        if (dt <= 0f) return value
        dt = dt.coerceAtMost(0.064f)
        val w0 = sqrt(stiffness)
        val z = dampingRatio
        val x0 = value - target
        val v0 = velocity
        val x: Float
        val v: Float
        if (z < 1f) {
            val wd = w0 * sqrt(1f - z * z)
            val e = exp(-z * w0 * dt)
            val a = x0
            val b = (z * w0 * x0 + v0) / wd
            val c = cos(wd * dt); val s = sin(wd * dt)
            x = e * (a * c + b * s)
            v = e * ((b * wd - z * w0 * a) * c - (a * wd + z * w0 * b) * s)
        } else {
            val e = exp(-w0 * dt)
            val b = v0 + w0 * x0
            x = (x0 + b * dt) * e
            v = (b - w0 * (x0 + b * dt)) * e
        }
        value = target + x
        velocity = v
        if (abs(x) < EPS && abs(v) < EPS * 10) snap(target)
        return value
    }

    private companion object {
        const val EPS = 0.002f
        const val MAX_V = 40000f
    }
}
