package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 最近: recent clips as a row of cards that scrolls sideways. A fling coasts with adjustable
 * friction and always comes to rest with a card aligned to the left edge, so the text being
 * read never stops half cut off. Tap pastes; long-press opens the menu.
 */
@SuppressLint("ViewConstructor")
class ClipStrip(context: Context, private val prefs: Prefs) : View(context) {
    data class Card(val text: String, val tag: String, val starred: Boolean)

    var theme: KbTheme = KbTheme.resolve(context, prefs)
    var title = "最近"
    var emptyText = ""
    var overlay: PopupOverlay? = null
    var onTap: ((Int) -> Unit)? = null
    var longOptions: ((Int) -> List<Output>)? = null
    var onLongOption: ((Int, Output) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null

    private var cards: List<Card> = emptyList()
    private val layouts = HashMap<Int, StaticLayout>()
    private val scroller = OverScroller(context)
    private var scrollPos = 0f
    private val appear = Tween(1f)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun setCards(list: List<Card>, keepPosition: Boolean) {
        cards = list
        layouts.clear()
        measureCards()
        if (!keepPosition) { scrollPos = 0f; scroller.forceFinished(true) }
        scrollPos = scrollPos.coerceIn(0f, maxScroll)
        if (prefs.animations && !keepPosition) { appear.set(0f); appear.animateTo(1f, (200 * prefs.animSpeed).toLong()) }
        invalidate()
    }

    private val titleH get() = dp(20f)
    private val pad get() = dp(10f)
    private val gap get() = dp(8f)
    /** Wide enough for a dozen characters a line; on wide screens two and a half cards show. */
    /** The widest a card gets: room for a dozen characters a line, two and a half cards on screen. */
    private val cardW get() = min(max(dp(132f), (width - pad) / 2.45f), dp(220f))
    private val minW get() = dp(68f)
    /** Left edge and width of each card: short clips get narrow cards, long ones wrap at [cardW]. */
    private var lefts = FloatArray(0)
    private var widths = FloatArray(0)
    private val step get() = cardW + gap
    private val maxScroll get() = if (cards.isEmpty()) 0f else max(0f, lefts.last() + widths.last() + pad - width)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layouts.clear(); measureCards() }

    private fun shown(t: String) = t.replace(Regex("\\s+"), " ").trim().let { if (it.length > prefs.clipMaxChars) it.take(prefs.clipMaxChars) + "…" else it }

    private fun measureCards() {
        lefts = FloatArray(cards.size); widths = FloatArray(cards.size)
        if (width == 0) return
        body.textSize = sp(Ui.BODY + 0.5f)
        tp.textSize = sp(Ui.CAPTION)
        val inner = dp(10f)
        var x = pad
        cards.forEachIndexed { i, card ->
            val textW = body.measureText(shown(card.text))
            val tagW = tp.measureText(listOfNotNull(if (card.starred) "★" else null, card.tag.takeIf { it.isNotEmpty() }).joinToString(" "))
            widths[i] = (max(textW, tagW) + 2 * inner + dp(2f)).coerceIn(minW, cardW)
            lefts[i] = x
            x += widths[i] + gap
        }
    }

    private fun rectOf(i: Int): RectF {
        val x = lefts[i] - scrollPos
        return RectF(x, titleH, x + widths[i], height - dp(6f))
    }

    /** The card whose left edge is nearest to scroll position [p]. */
    private fun nearest(p: Float): Int {
        var best = 0; var bd = Float.MAX_VALUE
        for (i in lefts.indices) { val d = abs(lefts[i] - pad - p); if (d < bd) { bd = d; best = i } }
        return best
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        if (scroller.computeScrollOffset()) { scrollPos = scroller.currX.toFloat(); postInvalidateOnAnimation() }
        val a = appear.value
        if (appear.running) postInvalidateOnAnimation()
        tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.SMALL); tp.color = theme.hint; tp.typeface = Ui.medium
        c.drawText(if (cards.isEmpty()) title else "$title ${cards.size}", pad + dp(2f), dp(14f), tp)
        tp.typeface = Ui.regular
        if (cards.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY)
            val fm = tp.fontMetrics
            c.drawText(emptyText, width / 2f, (titleH + height) / 2f - (fm.ascent + fm.descent) / 2, tp)
            return
        }
        c.save()
        c.translate((1 - a) * dp(18f), 0f)
        for (i in cards.indices) {
            val r = rectOf(i)
            if (r.right < 0 || r.left > width) continue
            drawCard(c, i, r, a)
        }
        c.restore()
        // edge fades say there is more
        if (scrollPos > 1f) fade(c, 0f, dp(14f), true)
        if (scrollPos < maxScroll - 1f) fade(c, width - dp(14f), width.toFloat(), false)
    }

    private fun fade(c: Canvas, l: Float, r: Float, left: Boolean) {
        val shader = android.graphics.LinearGradient(l, 0f, r, 0f,
            if (left) theme.background else (theme.background and 0x00FFFFFF),
            if (left) (theme.background and 0x00FFFFFF) else theme.background, android.graphics.Shader.TileMode.CLAMP)
        bg.shader = shader
        c.drawRect(l, titleH, r, height.toFloat(), bg)
        bg.shader = null
    }

    private fun drawCard(c: Canvas, i: Int, r: RectF, a: Float) {
        val card = cards[i]
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        bg.color = if (i == pressed) theme.surfacePressed else theme.surface
        bg.alpha = (255 * a).toInt()
        c.drawRoundRect(r, rad, rad, bg)
        val inner = dp(10f)
        var top = r.top + inner
        val tag = listOfNotNull(if (card.starred) "★" else null, card.tag.takeIf { it.isNotEmpty() }).joinToString(" ")
        if (tag.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.CAPTION); tp.color = theme.accent; tp.alpha = (255 * a).toInt()
            c.drawText(tag, r.left + inner, top + sp(Ui.CAPTION), tp)
            top += sp(Ui.BODY)
        }
        val w = (r.width() - 2 * inner).toInt().coerceAtLeast(1)
        val lay = layouts.getOrPut(i) {
            body.textSize = sp(Ui.BODY + 0.5f)
            body.color = theme.ink
            val text = card.text.replace(Regex("\\s+"), " ").trim().let { t ->
                if (t.length > prefs.clipMaxChars) t.take(prefs.clipMaxChars) + "…" else t
            }
            val lineH = body.fontSpacing * 1.08f
            val lines = ((r.bottom - inner - top) / lineH).toInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(text, 0, text.length, body, w)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).build()
        }
        c.save()
        c.translate(r.left + inner, top)
        lay.paint.alpha = (255 * a).toInt()
        lay.draw(c)
        c.restore()
    }

    // ---- touch ----
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressed = -1
    private var menuFor = -1
    private var tickIndex = 0
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private fun hit(x: Float, y: Float) = cards.indices.firstOrNull { rectOf(it).contains(x, y) } ?: -1

    private fun overlayOffset(): Pair<Float, Float> {
        val o = overlay ?: return 0f to 0f
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); o.getLocationInWindow(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    private val longRunnable = Runnable {
        val i = pressed
        if (i < 0 || dragging) return@Runnable
        val opts = longOptions?.invoke(i).orEmpty()
        if (opts.isEmpty()) return@Runnable
        val (ox, oy) = overlayOffset()
        val r = rectOf(i).apply { offset(ox, oy) }
        overlay?.showOptions(RectF(r.left, r.top, r.right, r.top + dp(44f)), dp(44f), opts, downX + ox, false)
        menuFor = i
        onFeedback?.invoke("popup")
        invalidate()
    }

    /** Where a fling of [v] px/s from here would come to rest, rounded to a card edge. */
    private fun snapTarget(v: Float): Float {
        // coasting distance under the friction setting; a stiffer feel stops sooner
        val coast = -v * 0.16f / prefs.clipFriction.coerceAtLeast(0.3f)
        if (cards.isEmpty()) return 0f
        return (lefts[nearest(scrollPos + coast)] - pad).coerceIn(0f, maxScroll)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastX = e.x; dragging = false; menuFor = -1
                pressed = hit(e.x, e.y)
                tickIndex = nearest(scrollPos)
                if (pressed >= 0) { onFeedback?.invoke("press"); handler.postDelayed(longRunnable, 420) }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuFor >= 0) {
                    val (ox, oy) = overlayOffset()
                    overlay?.moveSelection(e.x + ox, e.y + oy)
                    return true
                }
                if (!dragging && abs(e.x - downX) > slop) { dragging = true; pressed = -1; handler.removeCallbacks(longRunnable) }
                if (dragging) {
                    var p = scrollPos - (e.x - lastX)
                    // rubber band at both ends
                    if (p < 0f) p = scrollPos - (e.x - lastX) * 0.35f
                    if (p > maxScroll) p = scrollPos - (e.x - lastX) * 0.35f
                    scrollPos = p.coerceIn(-dp(60f), maxScroll + dp(60f))
                    val idx = nearest(scrollPos)
                    if (idx != tickIndex && scrollPos in 0f..maxScroll) { tickIndex = idx; onFeedback?.invoke("tick") }
                    invalidate()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                when {
                    menuFor >= 0 -> {
                        overlay?.selected()?.let { onLongOption?.invoke(menuFor, it) }
                        overlay?.hideOptions()
                        menuFor = -1
                    }
                    dragging -> {
                        vt?.computeCurrentVelocity(1000)
                        val target = snapTarget(vt?.xVelocity ?: 0f)
                        val dist = abs(target - scrollPos)
                        val ms = (220 + dist / step * 70).coerceAtMost(620f) * (if (prefs.animations) prefs.animSpeed else 0f)
                        scroller.startScroll(scrollPos.toInt(), 0, (target - scrollPos).toInt(), 0, ms.toInt())
                        postInvalidateOnAnimation()
                    }
                    pressed >= 0 -> onTap?.invoke(pressed)
                }
                pressed = -1
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (menuFor >= 0) { overlay?.hideOptions(); menuFor = -1 }
                if (scrollPos !in 0f..maxScroll) { val t = scrollPos.coerceIn(0f, maxScroll); scroller.startScroll(scrollPos.toInt(), 0, (t - scrollPos).toInt(), 0, 240) }
                pressed = -1; dragging = false; vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }
}
