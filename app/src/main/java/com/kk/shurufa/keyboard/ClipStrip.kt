package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
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
class ClipStrip(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** A clip: its text, or a picture ([image] is its thumbnail; null while it loads). */
    data class Card(val text: String, val tag: String, val starred: Boolean, val isImage: Boolean = false, val image: android.graphics.Bitmap? = null)

    var title = "最近"
    /** 清空 in the title line: the first tap asks (再点一次清空), a second within a few seconds clears. */
    var onClear: (() -> Unit)? = null
    private var clearAsked = 0L
    private val clearRect = RectF()
    private var clearDown = false
    private val clearAskMs = 2500L
    var emptyText = ""
    var overlay: PopupOverlay? = null
    var onTap: ((Int) -> Unit)? = null
    var longOptions: ((Int) -> List<Output>)? = null
    var onLongOption: ((Int, Output) -> Unit)? = null
    var onFeedback: ((String) -> Unit)? = null
    /** Vertical drags anywhere on the strip resize the clipboard (phase 0 start, 1 move, 2 end). */
    var onVerticalDrag: ((phase: Int, rawY: Float, velocityY: Float) -> Unit)? = null

    private var cards: List<Card> = emptyList()
    private val layouts = HashMap<Int, StaticLayout>()
    // decelerating only: the strip leaves the finger at its speed and slows down evenly
    private val scroller = OverScroller(context, android.view.animation.DecelerateInterpolator())
    private var scrollPos = 0f
    private val appear = Tween(1f)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun setCards(list: List<Card>, keepPosition: Boolean) {
        cards = list
        layouts.clear(); shaders.clear()
        measureCards()
        if (!keepPosition) { scrollPos = 0f; scroller.forceFinished(true) }
        scrollPos = scrollPos.coerceIn(0f, maxScroll)
        if (Motion.on(prefs) && !keepPosition) { appear.set(0f); appear.animateTo(1f, dur(Motion.SWAP + 60), Motion.ENTER) }
        invalidate()
    }

    private val titleH get() = dp(20f)
    private val pad get() = dp(10f)
    private val gap get() = dp(8f)
    /** The widest a card gets: room for a dozen characters a line, two and a half cards on screen. */
    private val cardW get() = min(max(dp(132f), (width - pad) / 2.45f), dp(220f))
    private val minW get() = dp(68f)
    /** Left edge and width of each card: short clips get narrow cards, long ones wrap at [cardW]. */
    private var lefts = FloatArray(0)
    private var widths = FloatArray(0)
    private val maxScroll get() = if (cards.isEmpty()) 0f else max(0f, lefts.last() + widths.last() + pad - width)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layouts.clear(); measureCards(); fadeL = null; fadeR = null }

    override fun onThemeChanged() { layouts.clear(); fadeL = null; fadeR = null }

    private fun shown(t: String) = t.replace(Regex("\\s+"), " ").trim().let { if (it.length > prefs.clipMaxChars) it.take(prefs.clipMaxChars) + "…" else it }

    private fun measureCards() {
        lefts = FloatArray(cards.size); widths = FloatArray(cards.size)
        if (width == 0) return
        body.textSize = sp(Ui.BODY)
        tp.textSize = sp(Ui.CAPTION)
        val inner = dp(10f)
        var x = pad
        val imageH = height - dp(6f) - titleH
        cards.forEachIndexed { i, card ->
            if (card.isImage) {
                // a picture keeps its shape, within the widths a text card can have
                val b = card.image
                widths[i] = (if (b == null) imageH else imageH * b.width / b.height.coerceAtLeast(1)).coerceIn(minW, cardW)
                lefts[i] = x; x += widths[i] + gap
                return@forEachIndexed
            }
            val textW = body.measureText(shown(card.text))
            val tagW = tp.measureText(listOfNotNull(if (card.starred) "★" else null, card.tag.takeIf { it.isNotEmpty() }).joinToString(" "))
            widths[i] = (max(textW, tagW) + 2 * inner + dp(2f)).coerceIn(minW, cardW)
            lefts[i] = x
            x += widths[i] + gap
        }
    }

    private val cardRect = RectF()
    /** Card [i] as it sits on screen now (one shared rect: copy it to keep it). */
    private fun rectOf(i: Int): RectF {
        val x = lefts[i] - scrollPos
        return cardRect.apply { set(x, titleH, x + widths[i], height - dp(6f)) }
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
        c.drawText(if (cards.isEmpty()) title else "$title ${cards.size}", pad + dp(2f), dp(15f), tp)
        tp.typeface = Ui.regular
        if (cards.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY)
            drawText(c, emptyText, width / 2f, (titleH + height) / 2f, tp)
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
        drawClear(c, a)
        drawProgress(c, a)
    }

    private fun drawClear(c: Canvas, a: Float) {
        if (onClear == null) { clearRect.setEmpty(); return }
        val asking = android.os.SystemClock.uptimeMillis() - clearAsked < clearAskMs
        val label = if (asking) "再点一次清空" else "清空"
        tp.textAlign = Paint.Align.RIGHT; tp.textSize = sp(Ui.SMALL); tp.typeface = if (asking) Ui.medium else Ui.regular
        tp.color = Colors.fade(if (asking) theme.accent else theme.hint, a * (if (clearDown) 0.5f else 1f))
        val right = width - pad - dp(2f)
        c.drawText(label, right, dp(15f), tp)
        val w = tp.measureText(label)
        clearRect.set(right - w - dp(10f), 0f, right + dp(6f), titleH + dp(2f))
        tp.textAlign = Paint.Align.LEFT; tp.typeface = Ui.regular
        if (asking) invalidateIn(clearAskMs - (android.os.SystemClock.uptimeMillis() - clearAsked) + 20)
    }

    /**
     * Where in the row the view is: a short track in the title line, on the right, its thumb as
     * wide as the share in view. Only drawn; touches there are the strip's as anywhere else.
     */
    private fun drawProgress(c: Canvas, a: Float) {
        val m = maxScroll
        if (m <= 0f) return
        val trackW = dp(40f); val h = dp(2.5f)
        val right = (if (clearRect.isEmpty) width - pad - dp(2f) else clearRect.left - dp(4f)); val left = right - trackW
        val cy = dp(15f) - sp(Ui.SMALL) * 0.33f
        val total = m + width
        val thumbW = (trackW * width / total).coerceAtLeast(dp(8f))
        val x = left + (trackW - thumbW) * (scrollPos / m).coerceIn(0f, 1f)
        progressPaint.color = Colors.fade(theme.hint, 0.25f * a)
        c.drawRoundRect(left, cy - h / 2, right, cy + h / 2, h / 2, h / 2, progressPaint)
        progressPaint.color = Colors.fade(theme.hint, 0.85f * a)
        c.drawRoundRect(x, cy - h / 2, x + thumbW, cy + h / 2, h / 2, h / 2, progressPaint)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var fadeL: LinearGradient? = null
    private var fadeR: LinearGradient? = null

    private fun fade(c: Canvas, l: Float, r: Float, left: Boolean) {
        val clear = Colors.alpha(theme.background, 0)
        val shader = if (left) fadeL ?: LinearGradient(l, 0f, r, 0f, theme.background, clear, Shader.TileMode.CLAMP).also { fadeL = it }
            else fadeR ?: LinearGradient(l, 0f, r, 0f, clear, theme.background, Shader.TileMode.CLAMP).also { fadeR = it }
        bg.shader = shader
        c.drawRect(l, titleH, r, height.toFloat(), bg)
        bg.shader = null
    }

    private val imgMatrix = android.graphics.Matrix()
    private val shaders = HashMap<android.graphics.Bitmap, android.graphics.BitmapShader>()

    private fun drawCard(c: Canvas, i: Int, r: RectF, a: Float) {
        val card = cards[i]
        val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
        bg.color = Colors.fade(if (i == pressed) theme.surfacePressed else theme.surface, a)
        c.drawRoundRect(r, rad, rad, bg)
        if (card.isImage) { drawImage(c, card, r, rad, a); return }
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
            body.textSize = sp(Ui.BODY)
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

    /** A picture card: the thumbnail filling it (cropped to its shape), dimmed while pressed. */
    private fun drawImage(c: Canvas, card: Card, r: RectF, rad: Float, a: Float) {
        val b = card.image
        if (b == null) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.CAPTION); tp.color = theme.hint; tp.alpha = (255 * a).toInt()
            drawText(c, "图片", r.centerX(), r.centerY(), tp)
            return
        }
        val scale = max(r.width() / b.width, r.height() / b.height)
        imgMatrix.setScale(scale, scale)
        imgMatrix.postTranslate(r.centerX() - b.width * scale / 2, r.centerY() - b.height * scale / 2)
        val shader = shaders.getOrPut(b) { android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        shader.setLocalMatrix(imgMatrix)
        bg.shader = shader; bg.alpha = (255 * a * if (cards.indexOf(card) == pressed) 0.8f else 1f).toInt()
        c.drawRoundRect(r, rad, rad, bg)
        bg.shader = null; bg.alpha = 255
        if (card.starred) {
            // the favorite's star on a small dark disc, readable over any picture
            val cx = r.left + dp(14f); val cy = r.top + dp(14f)
            bg.color = 0x66000000; c.drawCircle(cx, cy, dp(9f), bg)
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.CAPTION); tp.color = 0xFFFFFFFF.toInt()
            drawText(c, "★", cx, cy, tp)
        }
    }

    // ---- touch ----
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressed = -1
    private var vDrag = false
    private var downAt = 0L
    private var menuFor = -1
    private var tickIndex = 0
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(longRunnable)
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in cards.indices) if (rectOf(i).contains(x, y)) return i
        return -1
    }

    private val longRunnable = Runnable {
        val i = pressed
        if (i < 0 || dragging) return@Runnable
        val opts = longOptions?.invoke(i).orEmpty()
        if (opts.isEmpty()) return@Runnable
        locateIn(overlay)
        val r = onLayer(rectOf(i))
        overlay?.showOptions(RectF(r.left, r.top, r.right, r.top + dp(44f)), dp(44f), opts, downX + layerOffset[0])
        menuFor = i
        onFeedback?.invoke("popup")
        invalidate()
    }

    /** Where a fling of [v] px/s from here would come to rest, rounded to a card edge. */
    private fun snapTarget(v: Float): Float {
        // coasting distance under the friction setting; a stiffer feel stops sooner
        val coast = -v * 0.3f / prefs.clipFriction.coerceAtLeast(0.3f)
        if (cards.isEmpty()) return 0f
        return (lefts[nearest(scrollPos + coast)] - pad).coerceIn(0f, maxScroll)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (clearDown) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                clearDown = false
                if (e.actionMasked == MotionEvent.ACTION_UP && clearRect.contains(e.x, e.y)) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - clearAsked < clearAskMs) { clearAsked = 0L; onClear?.invoke() } else { clearAsked = now; onFeedback?.invoke("tick") }
                }
                invalidate()
            }
            return true
        }
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (cards.isNotEmpty() && clearRect.contains(e.x, e.y)) {
                // the 清空 words: a button of their own, nothing scrolls or pastes
                clearDown = true; invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            } else {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastX = e.x; dragging = false; menuFor = -1; vDrag = false
                downAt = android.os.SystemClock.uptimeMillis()
                pressed = hit(e.x, e.y)
                tickIndex = nearest(scrollPos)
                if (pressed >= 0) { onFeedback?.invoke("press"); handler.postDelayed(longRunnable, 420) }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuFor >= 0) {
                    locateIn(overlay)
                    overlay?.moveSelection(e.x + layerOffset[0], e.y + layerOffset[1])
                    return true
                }
                if (vDrag) { onVerticalDrag?.invoke(1, e.rawY, 0f); return true }
                if (!dragging && onVerticalDrag != null && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                    vDrag = true; pressed = -1; handler.removeCallbacks(longRunnable); invalidate()
                    onVerticalDrag?.invoke(0, e.rawY - (e.y - downY), 0f); onVerticalDrag?.invoke(1, e.rawY, 0f)
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
                if (vDrag) {
                    vt?.computeCurrentVelocity(1000)
                    onVerticalDrag?.invoke(2, e.rawY, vt?.yVelocity ?: 0f)
                    vDrag = false; pressed = -1; vt?.recycle(); vt = null; invalidate()
                    return true
                }
                val held = android.os.SystemClock.uptimeMillis() - downAt
                when {
                    menuFor >= 0 -> {
                        val o = overlay
                        val idx = menuFor
                        if (o == null || o.pickedByDrag()) {
                            o?.selected()?.let { onLongOption?.invoke(idx, it) }
                            o?.hideOptions()
                        } else o.stick { pick -> pick?.let { onLongOption?.invoke(idx, it) } }   // stays open for a tap
                        menuFor = -1
                    }
                    dragging -> {
                        vt?.computeCurrentVelocity(1000)
                        val target = snapTarget(vt?.xVelocity ?: 0f)
                        val dist = abs(target - scrollPos)
                        // with a decelerating curve the starting speed is 2·distance/time: match the finger
                        val v = abs(vt?.xVelocity ?: 0f)
                        val ms = (if (v > 50f) 2000f * dist / v else 220f).coerceIn(160f, 650f) * (if (Motion.on(prefs)) prefs.animSpeed else 0f)
                        scroller.startScroll(scrollPos.toInt(), 0, (target - scrollPos).toInt(), 0, ms.toInt())
                        postInvalidateOnAnimation()
                    }
                    // only a clean tap pastes: no slide, not held for long
                    pressed >= 0 && (prefs.holdCancelMs <= 0 || held < prefs.holdCancelMs) -> onTap?.invoke(pressed)
                }
                pressed = -1
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (vDrag) { onVerticalDrag?.invoke(2, e.rawY, 0f); vDrag = false }
                if (menuFor >= 0) { overlay?.hideOptions(); menuFor = -1 }
                if (scrollPos !in 0f..maxScroll) { val t = scrollPos.coerceIn(0f, maxScroll); scroller.startScroll(scrollPos.toInt(), 0, (t - scrollPos).toInt(), 0, 240) }
                pressed = -1; dragging = false; vt?.recycle(); vt = null; invalidate()
            }
        }
        return true
    }
}
