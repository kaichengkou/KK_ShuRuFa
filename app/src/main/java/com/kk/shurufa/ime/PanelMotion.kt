package com.kk.shurufa.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Motion
import kotlin.math.abs

/**
 * How panels change places under the bar: the one that leaves fades out where it is, the new one
 * rises in, and the area eases from the old height to the new. [content] is the area, set for
 * each input view.
 */
internal class PanelMotion(private val prefs: Prefs, private val density: () -> Float) {
    lateinit var content: FrameLayout

    /** Height told to the app (from the bottom) while the area moves; -1 means the column's current height. */
    var reportH = -1
        private set
    private var heightAnim: ValueAnimator? = null

    /** The panel fading out, drawn on the content's overlay: it is seen, but takes no touches. */
    private var ghost: View? = null
    /** The content's height when [ghost] left it. */
    private var ghostBase = 0

    /** A new input view: the fading panel belonged to the old one. */
    fun forgetGhost() { ghost = null }

    /** Stops the height easing; the app is told the column's height again. */
    fun stopHeight() {
        heightAnim?.cancel()
        reportH = -1
    }

    /** Eases the area's height from what it was ([oldTotal] with the bar) to what the new panel needs. */
    fun animateHeight(oldTotal: Int, newBar: Int, width: Int, navInset: Int) {
        if (!Motion.on(prefs) || oldTotal <= 0) return
        content.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val target = content.measuredHeight
        val start = oldTotal - newBar
        if (start <= 0 || abs(start - target) < 2f * density()) return
        // while it moves the app keeps the larger of the two sizes, so it lays out at most once
        reportH = maxOf(start, target) + newBar + navInset
        heightAnim = ValueAnimator.ofInt(start, target).apply {
            duration = Motion.dur(prefs, Motion.PANEL)
            interpolator = Motion.ENTER
            addUpdateListener {
                val h = it.animatedValue as Int
                content.layoutParams = content.layoutParams.apply { height = h }; content.requestLayout()
                // the leaving panel stays on the keyboard's bottom edge while the top edge moves
                ghost?.translationY = (h - ghostBase).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    reportH = -1
                    content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }; content.requestLayout()
                }
            })
            start()
        }
    }

    fun fadeOut(v: View) {
        if (!Motion.on(prefs) || !v.isLaidOut || v.alpha < 0.5f) return
        // whatever it was still doing (rising in) stops; from here the height animation moves it
        v.animate().cancel()
        v.translationY = 0f
        ghostBase = content.height
        content.overlay.add(v)
        ghost = v
        v.animate().alpha(0f).setDuration(Motion.dur(prefs, 150)).setInterpolator(Motion.EXIT).withLayer()
            .withEndAction { if (ghost === v) clearGhost() }.start()
    }

    fun clearGhost() {
        val g = ghost ?: return
        ghost = null
        g.animate().cancel()
        content.overlay.remove(g)
        g.alpha = 1f; g.translationY = 0f
    }

    /** A panel arrives: it rises a little into place as it fades in. */
    fun animateIn(v: View?) {
        if (v == null || !Motion.on(prefs)) return
        v.alpha = 0f; v.translationY = 14f * density()
        v.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(prefs, Motion.PANEL))
            .setInterpolator(Motion.ENTER).withLayer().start()
    }
}
