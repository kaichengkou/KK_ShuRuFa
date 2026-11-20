package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kk.shurufa.Prefs
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface KeyboardListener {
    /** A character key was tapped or flicked. [output] is what to type. */
    fun onOutput(key: Key, output: Output)
    /** A function key (shift, backspace, enter …) was tapped. */
    fun onFunction(key: Key)
    /** Options for the long-press popup; the first one is selected under the finger. */
    fun longOptions(key: Key): List<Output>
    fun onOption(key: Key, option: Output)
    fun onCursorMove(dx: Int, dy: Int)
    fun onCursorMode(active: Boolean)
    /** Space was swiped; return the new space label so the bar can animate to it. */
    fun onSwitchLanguage(direction: Int): String?
    fun onBackspaceRepeat()
    /** Swipe-left on backspace selects [words] words (0 = none); [commit] deletes them. */
    fun onBackspaceSwipe(words: Int, commit: Boolean)
    fun onPress(key: Key)
    fun shiftState(): Int
    fun spaceLabel(): String
}

/**
 * The key area: draws every key itself and turns touches into taps, flicks, long-press
 * selections and the space/backspace gestures. Popups are drawn on [overlay], which covers
 * the whole input view so they can rise above the top row.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val prefs: Prefs) : View(context) {

    var listener: KeyboardListener? = null
    var overlay: PopupOverlay? = null
    var theme: KbTheme = KbTheme.resolve(context, prefs)
    /** Math keys show four-direction hints and use a math font. */
    var mathMode = false
    /** State of the symbol panel's lock key. */
    var lockOn = false

    private var rows: List<List<Key>> = emptyList()
    private val rects = mutableListOf<Pair<Key, RectF>>()
    private val pressAnim = HashMap<Key, Tween>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dur(ms: Long) = if (prefs.animations) (ms * prefs.animSpeed).toLong() else 0L

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val handler = Handler(Looper.getMainLooper())

    // ---- layout -------------------------------------------------------------------------

    fun setRows(newRows: List<List<Key>>, math: Boolean = false) {
        val changed = newRows !== rows
        rows = newRows
        mathMode = math
        pressAnim.clear()
        requestLayout()
        if (changed && prefs.animations) {
            alpha = 0.55f
            animate().alpha(1f).setDuration(dur(140)).start()
        }
        invalidate()
    }

    private val keyH get() = dp(if (mathMode) prefs.keyHeightDp * 0.8f else prefs.keyHeightDp.toFloat())
    private val gap get() = dp(prefs.keyGapDp)
    private val padTop get() = dp(6f)
    private val padBottom get() = dp(prefs.bottomPaddingDp.toFloat() + 2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = rows.size
        val h = padTop + padBottom + n * keyH + max(0, n - 1) * gap
        setMeasuredDimension(w, h.roundToInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        rects.clear()
        val side = dp(3f)
        val avail = width - 2 * side
        var y = padTop
        for (row in rows) {
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            val unit = (avail - gap * (row.size - 1)) / total
            var x = side
            for (k in row) {
                val w = unit * k.width
                if (!k.spacer) rects += k to RectF(x, y, x + w, y + keyH)
                x += w + gap
            }
            y += keyH + gap
        }
    }

    // ---- drawing ------------------------------------------------------------------------

    private var labelsAlpha = Tween(1f)
    private val spaceOffset = Tween(0f)
    private var oldSpaceLabel: String? = null
    private var spaceSwapDir = 0
    private val spaceSwap = Tween(1f)

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val r = dp(prefs.keyRadiusDp)
        var animating = labelsAlpha.running || spaceOffset.running || spaceSwap.running
        val la = labelsAlpha.value
        for ((k, rect) in rects) {
            val press = pressAnim[k]
            val pv = press?.value ?: 0f
            if (press?.running == true) animating = true
            val isFn = k.style == KeyStyle.FUNC
            val deleting = k.fn == "backspace" && bsSwipeWords > 0
            // shadow line under the key, like the One UI keyboard
            if (prefs.keyBorders) {
                keyPaint.color = theme.border
                c.drawRoundRect(rect.left, rect.top + dp(1f), rect.right, rect.bottom + dp(1f), r, r, keyPaint)
            }
            val accentKey = deleting || (k.fn == "enter" && prefs.accentEnter)
            keyPaint.color = when {
                accentKey -> blend(theme.accent, theme.accentPressed, pv)
                else -> blend(if (isFn) theme.keyFn else theme.key, if (isFn) theme.keyFnPressed else theme.keyPressed, pv)
            }
            val shrink = dp(1.2f) * pv
            c.drawRoundRect(rect.left + shrink, rect.top + shrink, rect.right - shrink, rect.bottom - shrink, r, r, keyPaint)
            drawKeyContent(c, k, rect, la, if (accentKey) theme.onAccent else theme.ink)
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = ((a shr s and 0xff) + ((b shr s and 0xff) - (a shr s and 0xff)) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun shiftOn() = (listener?.shiftState() ?: 0) > 0

    fun displayLabel(k: Key): String {
        val m = k.main ?: return k.label ?: ""
        val s = k.label ?: m.label
        return if (!mathMode && k.isLetter && shiftOn()) s.uppercase() else s
    }

    private fun drawKeyContent(c: Canvas, k: Key, rect: RectF, labelAlpha: Float, ink: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val a = (255 * (if (k.fn == "space") 1f else labelAlpha)).toInt()
        textPaint.color = ink
        textPaint.alpha = a
        iconPaint.color = ink
        iconPaint.alpha = a
        if (k.fn != null) {
            when (k.fn) {
                "space" -> drawSpace(c, rect)
                "shift" -> {
                    val st = listener?.shiftState() ?: 0
                    Icons.draw(c, "shift", cx, cy, dp(22f), iconPaint, filled = st > 0)
                    if (st == 2) c.drawRect(cx - dp(6f), cy + dp(12f), cx + dp(6f), cy + dp(13.5f), iconPaint)
                }
                "lock" -> Icons.draw(c, if (lockOn) "lock" else "unlock", cx, cy, dp(22f), iconPaint)
                else -> if (k.label != null || !Icons.all.containsKey(k.fn)) {
                    textPaint.textSize = sp(15f * prefs.labelScale)
                    textPaint.typeface = Typeface.DEFAULT
                    drawCentered(c, k.label ?: k.fn, cx, cy, textPaint)
                } else Icons.draw(c, k.fn, cx, cy, dp(22f), iconPaint)
            }
            return
        }
        val label = displayLabel(k)
        val single = label.codePointCount(0, label.length) <= 1
        textPaint.typeface = if (mathMode) Typeface.SERIF else Typeface.DEFAULT
        textPaint.textSize = sp(
            when {
                mathMode && label.length > 3 -> 12.5f
                mathMode -> 17f
                single -> 22f
                else -> 16f
            } * prefs.labelScale
        )
        // keep long labels inside the key
        val maxW = rect.width() - dp(6f)
        val tw = textPaint.measureText(label)
        if (tw > maxW) textPaint.textSize *= maxW / tw
        if (!mathMode && single && !label[0].isLetterOrDigit()) drawByBounds(c, label, cx, cy, textPaint)
        else drawCentered(c, label, cx, cy + if (mathMode) 0f else dp(2.5f), textPaint)

        if (!prefs.showHints) return
        hintPaint.color = theme.hint
        hintPaint.alpha = (255 * labelAlpha).toInt()
        hintPaint.textSize = sp((if (mathMode) 9.5f else 10.5f) * prefs.hintScale)
        hintPaint.typeface = if (mathMode) Typeface.SERIF else Typeface.DEFAULT
        val fl = k.flicks
        if (mathMode || prefs.showAllHints) {
            fl[Dir.UP]?.let { drawCentered(c, it.label, cx, rect.top + hintPaint.textSize * 0.75f, hintPaint) }
            fl[Dir.DOWN]?.let { drawCentered(c, it.label, cx, rect.bottom - hintPaint.textSize * 0.45f, hintPaint) }
            fl[Dir.LEFT]?.let { drawCentered(c, it.label, rect.left + hintPaint.measureText(it.label) / 2 + dp(3f), cy, hintPaint) }
            fl[Dir.RIGHT]?.let { drawCentered(c, it.label, rect.right - hintPaint.measureText(it.label) / 2 - dp(3f), cy, hintPaint) }
        } else {
            fl[Dir.UP]?.let {
                val h = hintLabel(it.label)
                val w = hintPaint.measureText(h)
                drawCentered(c, h, rect.right - w / 2 - dp(6f), rect.top + dp(4f) + hintPaint.textSize * 0.5f, hintPaint)
            }
        }
    }

    /** Short form of a hint so it stays in the key's corner (—— is shown as one dash). */
    private fun hintLabel(s: String) = if (s == "——") "—" else s

    private val bounds = android.graphics.Rect()
    private fun drawByBounds(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        p.getTextBounds(s, 0, s.length, bounds)
        // paint is center-aligned, bounds are relative to a left-aligned origin
        c.drawText(s, x + p.measureText(s) / 2 - bounds.exactCenterX(), y - bounds.exactCenterY(), p)
    }

    private fun drawSpace(c: Canvas, rect: RectF) {
        textPaint.textSize = sp(13.5f * prefs.labelScale)
        textPaint.color = theme.hint
        textPaint.typeface = Typeface.DEFAULT
        c.save()
        c.clipRect(rect)
        if (cursorMode) {
            textPaint.alpha = 255
            drawCentered(c, "◂  光标  ▸", rect.centerX(), rect.centerY(), textPaint)
        } else {
            val label = listener?.spaceLabel() ?: ""
            val off = spaceOffset.value
            val p = spaceSwap.value
            val old = oldSpaceLabel
            if (old != null && p < 1f) {
                textPaint.alpha = (255 * (1 - p)).toInt()
                drawCentered(c, old, rect.centerX() + spaceSwapDir * p * rect.width() * 0.5f, rect.centerY(), textPaint)
                textPaint.alpha = (255 * p).toInt()
                drawCentered(c, label, rect.centerX() - spaceSwapDir * (1 - p) * rect.width() * 0.5f, rect.centerY(), textPaint)
            } else {
                textPaint.alpha = (255 * (1 - min(1f, abs(off) / rect.width()) * 0.5f)).toInt()
                drawCentered(c, label, rect.centerX() + off, rect.centerY(), textPaint)
                if (abs(off) > dp(8f)) {
                    // arrows hint that releasing switches the language
                    hintPaint.color = theme.hint
                    hintPaint.alpha = (255 * min(1f, abs(off) / dp(40f))).toInt()
                    hintPaint.textSize = sp(12f)
                    drawCentered(c, if (off > 0) "‹" else "›", if (off > 0) rect.left + dp(14f) else rect.right - dp(14f), rect.centerY(), hintPaint)
                }
            }
        }
        c.restore()
    }

    private fun drawCentered(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, p)
    }

    // ---- touch --------------------------------------------------------------------------

    private enum class Mode { NONE, PRESS, POPUP, CURSOR, SPACE_SWIPE, BS_SWIPE, BS_REPEAT }

    private var mode = Mode.NONE
    private var active: Key? = null
    private var activeRect: RectF? = null
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var flick: Dir? = null
    private var cursorMode = false
    private var cursorAccX = 0f
    private var cursorAccY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var bsSwipeWords = 0

    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        var interval = 0L
        override fun run() {
            listener?.onBackspaceRepeat()
            interval = max(25L, (interval * 0.88f).toLong())
            handler.postDelayed(this, interval)
        }
    }

    private fun keyAt(x: Float, y: Float): Pair<Key, RectF>? {
        rects.firstOrNull { it.second.contains(x, y) }?.let { return it }
        // fall back to the nearest key so taps in the gaps still count
        return rects.minByOrNull { (_, r) ->
            val dx = max(0f, max(r.left - x, x - r.right))
            val dy = max(0f, max(r.top - y, y - r.bottom))
            dx * dx + dy * dy
        }?.takeIf { (_, r) -> abs(r.centerY() - y) < keyH }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // fast typing: a second finger finishes the first key as a tap
                if (mode == Mode.PRESS) finish(commit = true)
                start(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pointerId) finish(commit = true)
            MotionEvent.ACTION_UP -> finish(commit = true)
            MotionEvent.ACTION_CANCEL -> finish(commit = false)
        }
        return true
    }

    private fun start(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        val hit = keyAt(x, y) ?: return
        pointerId = e.getPointerId(index)
        active = hit.first
        activeRect = hit.second
        downX = x; downY = y; lastX = x; lastY = y
        flick = null
        mode = Mode.PRESS
        val k = hit.first
        pressAnim.getOrPut(k) { Tween() }.animateTo(1f, dur(45))
        invalidate()
        listener?.onPress(k)
        if (k.main != null && prefs.keyPreview) overlay?.showPreview(previewRect(hit.second), displayLabel(k), null, mathMode)
        val delay = when (k.fn) {
            "backspace" -> 380L
            else -> prefs.longPressMs.toLong()
        }
        handler.postDelayed(longPressRunnable, delay)
    }

    private fun previewRect(r: RectF): RectF {
        val loc = overlayOffset()
        val w = max(r.width() * 1.1f, dp(46f))
        val h = keyH * 1.15f
        val cx = r.centerX() + loc.first
        val top = r.top + loc.second - h - dp(4f)
        return RectF(cx - w / 2, top, cx + w / 2, top + h)
    }

    /** Position of this view inside the overlay. */
    private fun overlayOffset(): Pair<Float, Float> {
        val o = overlay ?: return 0f to 0f
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); o.getLocationInWindow(b)
        return (a[0] - b[0]).toFloat() to (a[1] - b[1]).toFloat()
    }

    private fun move(x: Float, y: Float) {
        val k = active ?: return
        val dx = x - downX
        val dy = y - downY
        val threshold = dp(prefs.flickDp)
        when (mode) {
            Mode.POPUP -> {
                val o = overlayOffset()
                overlay?.moveSelection(x + o.first, y + o.second)
            }
            Mode.CURSOR -> {
                val step = dp(prefs.cursorStepDp)
                cursorAccX += x - lastX
                cursorAccY += y - lastY
                val sx = (cursorAccX / step).toInt()
                val sy = (cursorAccY / (step * 3.5f)).toInt()
                if (sx != 0 || sy != 0) {
                    listener?.onCursorMove(sx, sy)
                    cursorAccX -= sx * step
                    cursorAccY -= sy * step * 3.5f
                }
            }
            Mode.SPACE_SWIPE -> {
                val w = activeRect?.width() ?: width.toFloat()
                spaceOffset.set((dx * 0.7f).coerceIn(-w * 0.45f, w * 0.45f))
                invalidate()
            }
            Mode.BS_SWIPE -> {
                val words = max(0, ((-dx - threshold) / dp(28f)).toInt() + 1)
                if (words != bsSwipeWords) {
                    bsSwipeWords = words
                    listener?.onBackspaceSwipe(words, false)
                    listener?.onPress(k)
                    invalidate()
                }
            }
            Mode.BS_REPEAT -> {}
            Mode.PRESS -> {
                if (hypot(dx, dy) < threshold) {
                    if (flick != null) {
                        flick = null
                        if (k.main != null && prefs.keyPreview) overlay?.showPreview(previewRect(activeRect!!), displayLabel(k), null, mathMode)
                    }
                } else {
                    handler.removeCallbacks(longPressRunnable)
                    when {
                        k.fn == "space" && abs(dx) > abs(dy) -> when (prefs.spaceSwipe) {
                            "language" -> { mode = Mode.SPACE_SWIPE; move(x, y); return }
                            "cursor" -> { enterCursor(); lastX = downX; move(x, y); return }
                        }
                        k.fn == "backspace" && dx < 0 && prefs.backspaceSwipe -> {
                            mode = Mode.BS_SWIPE
                            move(x, y)
                        }
                        k.main != null -> {
                            val d = if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT) else (if (dy > 0) Dir.DOWN else Dir.UP)
                            if (d != flick) {
                                flick = d
                                val out = k.flicks[d]
                                if (prefs.keyPreview) overlay?.showPreview(previewRect(activeRect!!), out?.label ?: displayLabel(k), if (out != null) d else null, mathMode)
                            }
                        }
                    }
                }
            }
            Mode.NONE -> {}
        }
        lastX = x; lastY = y
    }

    private fun enterCursor() {
        mode = Mode.CURSOR
        cursorMode = true
        cursorAccX = 0f; cursorAccY = 0f
        overlay?.hidePreview()
        labelsAlpha.animateTo(0.25f, dur(160))
        listener?.onCursorMode(true)
        invalidate()
    }

    private fun onLongPress() {
        val k = active ?: return
        if (mode != Mode.PRESS) return
        when {
            k.fn == "space" -> if (prefs.spaceLongPress == "cursor") enterCursor()
            k.fn == "backspace" -> {
                mode = Mode.BS_REPEAT
                listener?.onBackspaceRepeat()
                repeatRunnable.interval = prefs.backspaceRepeatMs * 2L
                handler.postDelayed(repeatRunnable, repeatRunnable.interval)
            }
            else -> {
                val opts = listener?.longOptions(k).orEmpty()
                if (opts.isEmpty()) return
                mode = Mode.POPUP
                overlay?.hidePreview()
                val o = overlayOffset()
                val r = RectF(activeRect).apply { offset(o.first, o.second) }
                overlay?.showOptions(r, keyH, opts, lastX + o.first, mathMode)
                listener?.onPress(k)
            }
        }
    }

    private fun finish(commit: Boolean) {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        val k = active
        val r = activeRect
        if (k != null) pressAnim[k]?.animateTo(0f, dur(170))
        overlay?.hidePreview()
        if (k != null && commit) when (mode) {
            Mode.PRESS -> {
                val fl = flick
                when {
                    k.main != null -> listener?.onOutput(k, if (fl != null) k.flicks[fl] ?: k.main else k.main)
                    else -> listener?.onFunction(k)
                }
            }
            Mode.POPUP -> overlay?.selected()?.let { listener?.onOption(k, it) }
            Mode.SPACE_SWIPE -> {
                val off = spaceOffset.value
                if (abs(off) > dp(24f) && r != null) {
                    val dir = if (off < 0) 1 else -1
                    oldSpaceLabel = listener?.spaceLabel()
                    val nl = listener?.onSwitchLanguage(dir)
                    if (nl != null) {
                        spaceSwapDir = if (off < 0) -1 else 1
                        spaceSwap.set(0f)
                        spaceSwap.animateTo(1f, dur(240), Tween.EASE_IN_OUT)
                    }
                }
                spaceOffset.animateTo(0f, dur(180))
            }
            Mode.BS_SWIPE -> {
                listener?.onBackspaceSwipe(bsSwipeWords, true)
                bsSwipeWords = 0
            }
            else -> {}
        } else if (mode == Mode.BS_SWIPE) {
            listener?.onBackspaceSwipe(0, false)
            bsSwipeWords = 0
        }
        if (mode == Mode.POPUP) overlay?.hideOptions()
        if (mode == Mode.SPACE_SWIPE && !commit) spaceOffset.animateTo(0f, dur(180))
        if (cursorMode) {
            cursorMode = false
            labelsAlpha.animateTo(1f, dur(200))
            listener?.onCursorMode(false)
        }
        mode = Mode.NONE
        active = null
        activeRect = null
        pointerId = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
