package com.kk.shurufa.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import com.kk.shurufa.Prefs

/**
 * What every surface the keyboard draws itself shares: the theme, unit conversion, the motion
 * settings, text drawn on its visual center, the one chip component, tap targets, and where the
 * view sits on the popup layer. Nothing here allocates while drawing.
 */
abstract class KbView(context: Context, protected val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
        set(v) { field = v; onThemeChanged(); invalidate() }

    /** Called after [theme] changed; caches that depend on colors are dropped here. */
    protected open fun onThemeChanged() {}

    private val metrics = resources.displayMetrics
    protected fun dp(v: Float) = v * metrics.density
    protected fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)
    /** [ms] under the user's animation settings (0 when off). */
    protected fun dur(ms: Long) = Motion.dur(prefs, ms)

    /** A frame of something moving: drawn on the next vsync, at the screen's highest rate ([FrameRate]). */
    override fun postInvalidateOnAnimation() { FrameRate.moving(this); super.postInvalidateOnAnimation() }

    private val redraw = Runnable { invalidate() }
    /** One redraw [ms] from now, replacing any planned before (timed redraws never pile up). */
    fun invalidateIn(ms: Long) { removeCallbacks(redraw); if (ms <= 0L) super.postInvalidateOnAnimation() else postDelayed(redraw, ms) }

    // ---- text ----
    private val fm = Paint.FontMetrics()

    /** Draws [s] so that its glyphs are centered on [cy]; x follows the paint's alignment. */
    protected fun drawText(c: Canvas, s: CharSequence, x: Float, cy: Float, p: Paint) {
        p.getFontMetrics(fm)
        c.drawText(s, 0, s.length, x, cy - (fm.ascent + fm.descent) / 2, p)
    }

    /** The baseline that centers text of [p] on [cy]. */
    protected fun baselineFor(cy: Float, p: Paint): Float {
        p.getFontMetrics(fm)
        return cy - (fm.ascent + fm.descent) / 2
    }

    // ---- the popup layer ----
    private val locA = IntArray(2)
    private val locB = IntArray(2)
    /** Offset of this view inside the popup layer, set by [locateIn]: x in [0], y in [1]. */
    protected val layerOffset = FloatArray(2)

    /** Updates [layerOffset] for [layer]; false when there is none. */
    protected fun locateIn(layer: View?): Boolean {
        if (layer == null) { layerOffset[0] = 0f; layerOffset[1] = 0f; return false }
        getLocationInWindow(locA); layer.getLocationInWindow(locB)
        layerOffset[0] = (locA[0] - locB[0]).toFloat(); layerOffset[1] = (locA[1] - locB[1]).toFloat()
        return true
    }

    /** A copy of [r] moved onto the popup layer (call [locateIn] first). */
    protected fun onLayer(r: RectF) = RectF(r).apply { offset(layerOffset[0], layerOffset[1]) }

    // ---- chips ----
    enum class ChipStyle {
        /** Soft fill: an option or a control. */
        SOFT,
        /** Translucent accent: the selected option, an open menu. */
        ACTIVE,
        /** Accent fill: the one thing to do next. */
        PRIMARY,
        /** No fill until pressed: a quiet control (取消, ×). */
        GHOST,
    }

    protected val chipBg = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val chipText = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val chipIcon = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Width of a chip for [label] with the given decorations. Measured in the medium weight, so a
     * chip keeps its width when it becomes the selected one.
     */
    protected fun chipWidth(label: String, icon: String? = null, chevron: Boolean = false, textSp: Float = Ui.BODY): Float {
        if (label.isEmpty()) return dp(48f)
        chipText.textSize = sp(textSp)
        chipText.typeface = Ui.medium
        var w = chipText.measureText(label) + 2 * dp(Ui.CHIP_PAD)
        if (icon != null) w += dp(Ui.CHIP_ICON) + dp(4f)
        if (chevron) w += dp(12f)
        return w
    }

    /**
     * Draws the keyboard's one chip into [r]: a pill with an optional leading icon and a trailing
     * chevron (for chips that open a list). [alpha] fades the whole chip.
     */
    protected fun drawChip(
        c: Canvas, r: RectF, label: String, style: ChipStyle, pressed: Boolean = false,
        icon: String? = null, chevron: Boolean = false, textSp: Float = Ui.BODY, alpha: Float = 1f,
    ) {
        val t = theme
        val fill = when (style) {
            ChipStyle.SOFT -> if (pressed) t.softPressed else t.soft
            ChipStyle.ACTIVE -> if (pressed) Colors.blend(t.accentSoft, t.accent, 0.25f) else t.accentSoft
            ChipStyle.PRIMARY -> if (pressed) t.accentPressed else t.accent
            ChipStyle.GHOST -> if (pressed) t.soft else 0
        }
        val ink = when (style) {
            ChipStyle.ACTIVE -> t.accent
            ChipStyle.PRIMARY -> t.onAccent
            else -> t.inkSoft
        }
        if (fill != 0) {
            chipBg.color = Colors.fade(fill, alpha)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, chipBg)
        }
        chipText.textSize = sp(textSp)
        chipText.typeface = if (style == ChipStyle.PRIMARY || style == ChipStyle.ACTIVE) Ui.medium else Ui.regular
        chipText.color = Colors.fade(ink, alpha)
        chipIcon.color = chipText.color
        val iconPx = dp(Ui.CHIP_ICON)
        val chevW = if (chevron) dp(10f) else 0f
        if (label.isEmpty()) {
            if (icon != null) Icons.draw(c, icon, r.centerX(), r.centerY(), iconPx * 1.15f, chipIcon)
            return
        }
        val textW = chipText.measureText(label)
        val iconW = if (icon != null) iconPx + dp(4f) else 0f
        val content = iconW + textW + (if (chevron) dp(2f) + chevW else 0f)
        var x = r.centerX() - content / 2
        if (icon != null) { Icons.draw(c, icon, x + iconPx / 2, r.centerY(), iconPx, chipIcon); x += iconW }
        chipText.textAlign = Paint.Align.LEFT
        drawText(c, label, x, r.centerY(), chipText)
        x += textW
        if (chevron) Icons.draw(c, "chevron", x + dp(2f) + chevW / 2, r.centerY() + dp(0.5f), chevW + dp(4f), chipIcon)
    }
}

/**
 * Tap targets collected while drawing, reused from frame to frame. The last one added wins where
 * targets overlap (what is drawn on top is what gets the tap).
 */
class Hits {
    private val rects = ArrayList<RectF>()
    private val ids = ArrayList<String>()
    var size = 0
        private set

    fun clear() { size = 0 }

    /** Records [id] for the area [l, t, r, b], grown by [slop] on every side. */
    fun add(id: String, l: Float, t: Float, r: Float, b: Float, slop: Float = 0f) {
        if (size == rects.size) { rects += RectF(); ids += id }
        rects[size].set(l - slop, t - slop, r + slop, b + slop)
        ids[size] = id
        size++
    }

    fun add(id: String, r: RectF, slop: Float = 0f) = add(id, r.left, r.top, r.right, r.bottom, slop)

    fun at(x: Float, y: Float): String? {
        for (i in size - 1 downTo 0) if (rects[i].contains(x, y)) return ids[i]
        return null
    }

    fun rect(id: String): RectF? {
        for (i in size - 1 downTo 0) if (ids[i] == id) return rects[i]
        return null
    }
}
