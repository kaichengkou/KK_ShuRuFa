package com.kk.shurufa.keyboard

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window

/**
 * The keyboard draws in step with the screen (each frame comes on the display's own vsync), and
 * every animation runs on time, not on frame counts, so it is as smooth at 120 Hz as at 60.
 * What this adds: while something moves, it asks for the screen's highest refresh rate, so a
 * panel or a spring that goes on after the finger lifts (when an adaptive screen may already
 * have dropped to 60 or lower) stays at 120; once nothing moves, the request is let go and the
 * screen falls back as low as it likes. Timed frames (the shark's) never ask.
 *
 * Android 15+: the view's own frame-rate vote. Android 11–14: the input window's preferred
 * display mode, the highest-rate mode at the current resolution.
 */
object FrameRate {
    private const val HOLD_MS = 350L
    private val main = Handler(Looper.getMainLooper())
    /** The input method's window (Android 11–14). */
    var window: Window? = null
        set(v) { release.run(); field = v }
    private var high = false

    private val release = Runnable {
        if (!high) return@Runnable
        high = false
        val w = window ?: return@Runnable
        runCatching { w.attributes = w.attributes.also { it.preferredDisplayModeId = 0 } }
    }

    /** Something on [v] is moving this frame. */
    fun moving(v: View) {
        if (Build.VERSION.SDK_INT >= 35) return vote(v)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val w = window ?: return
        main.removeCallbacks(release)
        main.postDelayed(release, HOLD_MS)
        if (high) return
        val mode = bestMode(v) ?: return
        high = true
        runCatching { w.attributes = w.attributes.also { it.preferredDisplayModeId = mode } }
    }

    /** Lets go at once (the keyboard went away). */
    fun idle() { main.removeCallbacks(release); release.run() }

    private fun bestMode(v: View): Int? {
        val d = v.display ?: return null
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return null
        return best.modeId.takeIf { best.refreshRate > 61f }
    }

    private val resets = HashMap<View, Runnable>()

    @androidx.annotation.RequiresApi(35)
    private fun vote(v: View) {
        if (v.requestedFrameRate != View.REQUESTED_FRAME_RATE_CATEGORY_HIGH) v.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        val r = resets.getOrPut(v) { Runnable { v.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT; resets.remove(v) } }
        main.removeCallbacks(r)
        main.postDelayed(r, HOLD_MS)
    }
}
