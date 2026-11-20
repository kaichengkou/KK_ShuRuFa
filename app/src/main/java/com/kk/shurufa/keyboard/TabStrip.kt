package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs

/**
 * A row of tabs that share the width evenly. The highlight slides from the old tab to the new
 * one; tapping anywhere in a tab's slot selects it.
 */
@SuppressLint("ViewConstructor")
class TabStrip(context: Context, private val prefs: Prefs) : View(context) {
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var onTab: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null

    private var tabs: List<Pair<String, String>> = emptyList()
    private var current = ""
    private val pill = Tween(0f)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setTabs(list: List<Pair<String, String>>, selected: String) {
        val first = tabs.isEmpty() || list != tabs
        tabs = list
        val i = list.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        if (first || current.isEmpty()) pill.set(i.toFloat())
        else if (selected != current) pill.animateTo(i.toFloat(), if (prefs.animations) (240 * prefs.animSpeed).toLong() else 0L)
        current = selected
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(38f).toInt())
    }

    private val slot get() = (width - dp(8f)) / tabs.size.coerceAtLeast(1)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        if (tabs.isEmpty()) return
        if (pill.running) postInvalidateOnAnimation()
        val w = slot
        val px = dp(4f) + pill.value * w
        val r = RectF(px + dp(3f), dp(5f), px + w - dp(3f), height - dp(5f))
        bg.color = theme.accentSoft
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, bg)
        tp.textSize = sp(Ui.BODY)
        val fm = tp.fontMetrics
        tabs.forEachIndexed { i, (id, name) ->
            val on = id == current
            tp.typeface = if (on) Ui.medium else Ui.regular
            tp.color = if (on) theme.accent else theme.inkSoft
            c.drawText(name, dp(4f) + i * w + w / 2, height / 2f - (fm.ascent + fm.descent) / 2, tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val i = ((e.x - dp(4f)) / slot).toInt().coerceIn(0, tabs.size - 1)
            val id = tabs.getOrNull(i)?.first ?: return true
            if (id != current) { onFeedback?.invoke(); onTab?.invoke(id) }
        }
        return true
    }
}
