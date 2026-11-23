package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    /** Options for the long-press popup. */
    fun longOptions(key: Key): List<Output>
    /** Long-press options in rows: the first row sits right above the key. */
    fun longOptionRows(key: Key): List<List<Output>> = listOf(longOptions(key))
    fun onOption(key: Key, option: Output)
    fun onCursorMove(dx: Int, dy: Int)
    fun onCursorMode(active: Boolean)
    /** Space was swiped; return the new space label so the bar can animate to it. */
    fun onSwitchLanguage(direction: Int): String?
    fun onBackspaceRepeat()
    /** Backspace swiped up ("clear") or down ("undo"). */
    fun onBackspaceGesture(action: String)
    fun onPress(key: Key)
    /** A long-press popup opened or a gesture was armed; used for haptics. */
    fun onFeedback(kind: String) {}
    fun shiftState(): Int
    fun spaceLabel(): String
    /** Whether 小K sits on the space key, before the language. */
    fun kayOnSpace(): Boolean = false
}

/**
 * The key area: draws every key itself and turns touches into taps, flicks, long-press
 * selections and the space/backspace gestures. Popups are drawn on [overlay], which covers
 * the whole input view so they can rise above the top row.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, prefs: Prefs) : KbView(context, prefs) {

    var listener: KeyboardListener? = null
    /** 落点自适应: where this person's taps land on each letter; null reads taps by the key shapes alone. */
    var touch: TouchModel? = null
    /** Whether taps teach the touch model where this person hits the keys (not in 临时模式 or passwords). */
    var learnTaps = true
    var overlay: PopupOverlay? = null
    /** Math keys show four-direction hints and use a math font. */
    var mathMode = false
        private set
    /** State of the symbol panel's lock key. */
    var lockOn = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Height of one key, as a fraction of the configured height (smaller grids use less). */
    var heightScale = 1f
    /** Space above the first row and below the last; stacked keyboards use less. */
    var padTopDp = 6f
    var padBottom = true
    /** Overrides the space swipe setting for this keyboard ("cursor" on the academic panel). */
    var spaceSwipeOverride: String? = null

    private var rows: List<List<Key>> = emptyList()
    /** Where the spacer keys are (space left for another view, like the number pad's operator strip). */
    val spacerRects = mutableListOf<RectF>()
    var onLaidOut: (() -> Unit)? = null
    private val keys = mutableListOf<Key>()
    private val rects = mutableListOf<RectF>()

    /** Where the first key matching [pred] is laid out, if any. */
    fun keyRect(pred: (Key) -> Boolean): RectF? = keys.indexOfFirst(pred).takeIf { it >= 0 }?.let { rects[it] }
    private val pressAnim = HashMap<Key, Tween>()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val handler = Handler(Looper.getMainLooper())

    // ---- layout -------------------------------------------------------------------------

    /**
     * 换语言: the keyboard that leaves slides out [slide] (−1 left, 1 right) and fades, the new one
     * slides in from the other side. The old one is a snapshot taken just before; it goes when the
     * slide ends.
     */
    private var ghost: android.graphics.Bitmap? = null
    private val swap = Tween(1f)

    // a function key's icon plays its little motion after a tap (删除 nudges, 回车 pushes …)
    private var tappedKey: Key? = null
    private val keyTapT = Tween(1f)
    private fun tapped(k: Key) {
        if (!Motion.on(prefs) || k.fn == null) return
        tappedKey = k; keyTapT.set(0f); keyTapT.animateTo(1f, dur(KEY_TAP_MS), Motion.LINEAR); postInvalidateOnAnimation()
    }
    private fun tapOf(k: Key) = if (k === tappedKey) keyTapT.value else 1f
    private var swapDir = 0
    private val ghostPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    fun setRows(newRows: List<List<Key>>, math: Boolean = false, slide: Int = 0) {
        if (slide != 0 && newRows !== rows && Motion.on(prefs) && isAttachedToWindow && width > 0 && height > 0) {
            ghost?.recycle()
            ghost = runCatching {
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
            }.getOrNull()
            swapDir = slide
            swap.set(0f); swap.animateTo(1f, dur(Motion.PANEL), Motion.MOVE)
        }
        val changed = newRows !== rows
        rows = newRows
        mathMode = math
        pressAnim.clear()
        requestLayout()
        if (changed && isAttachedToWindow && Motion.on(prefs) && ghost == null) {
            // a new set of keys: the labels arrive, the key shapes stay where they are
            labelsAlpha.set(0.35f)
            labelsAlpha.animateTo(1f, dur(Motion.SWAP), Motion.ENTER)
        }
        invalidate()
    }

    private val keyH get() = dp(prefs.keyHeightDp.toFloat() * heightScale)
    private val gap get() = dp(prefs.keyGapDp)
    private val rowGap get() = dp(prefs.rowGapDp)
    private val padTop get() = dp(padTopDp)
    private val padBottomPx get() = if (padBottom) dp(prefs.bottomPaddingDp.toFloat() + 2f) else 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = rows.size
        val natural = (padTop + padBottomPx + n * keyH + max(0, n - 1) * rowGap).roundToInt()
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else natural
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        keys.clear(); rects.clear(); spacerRects.clear()
        val side = dp(4f)
        val avail = width - 2 * side
        val n = rows.size
        val g = gap
        // keys fill the height the parent gives: taller than natural stretches them, shorter (five
        // number rows in the letters' height) makes them a little shorter instead of cutting a row off
        val rg = rowGap
        val kh = if (n > 0 && height > 0) ((height - padTop - padBottomPx - (n - 1) * rg) / n).coerceAtLeast(dp(24f)) else keyH
        var y = padTop
        for (row in rows) {
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            val unit = (avail - g * (row.size - 1)) / total
            var x = side
            for (k in row) {
                val w = unit * k.width
                if (!k.spacer) { keys += k; rects += RectF(x, y, x + w, y + kh) } else spacerRects += RectF(x, y, x + w, y + kh)
                x += w + g
            }
            y += kh + rg
        }
        surfaceShader = null
        onLaidOut?.invoke()
    }

    // ---- drawing ------------------------------------------------------------------------

    private val labelsAlpha = Tween(1f)
    /** 0 normally, 1 while space works as a trackpad: every key turns into one quiet surface. */
    private val trackpad = Tween(0f)
    private val spaceOffset = Spring(0f, stiffness = 900f, dampingRatio = 0.8f)
    private var oldSpaceLabel: String? = null
    private var spaceSwapDir = 0
    private val spaceSwap = Tween(1f)
    private var bsArmed: String? = null   // "clear" | "undo" while the backspace gesture is armed
    private val tmpRect = RectF()
    private val bounds = Rect()

    /**
     * The keys' material: light from above, so a key's face is a touch brighter at the top than
     * at the bottom (a few percent, felt more than seen), with a hairline of light along its top
     * edge at night. One shader serves every key of a row height; it is moved to each key.
     */
    private var surfaceShader: android.graphics.LinearGradient? = null
    private var shaderFor = 0
    private val shaderMatrix = android.graphics.Matrix()

    private fun surfaceShader(t: KbTheme, h: Float): android.graphics.Shader {
        val key = t.surface * 31 + h.toInt()
        surfaceShader?.let { if (shaderFor == key) return it }
        // 纸墨 is matte paper: no sheen, at most a breath of shading; the modern keys are molded
        val paper = prefs.style == "paper"
        val top = if (t.dark && !paper) Colors.blend(t.surface, android.graphics.Color.WHITE, 0.045f) else t.surface
        val bottom = if (paper) (if (t.dark) t.surface else Colors.blend(t.surface, t.background, 0.04f))
            else if (t.dark) t.surface else Colors.blend(t.surface, t.background, 0.22f)
        return android.graphics.LinearGradient(0f, 0f, 0f, h, top, bottom, android.graphics.Shader.TileMode.CLAMP)
            .also { surfaceShader = it; shaderFor = key }
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        if (prefs.style == "paper") Paper.grain(c, width, height, t.dark)
        val g = ghost
        val sp = swap.value
        val travel = dp(44f)
        if (g != null) { c.save(); c.translate(swapDir * (1f - sp) * travel, 0f) }
        val r = dp(prefs.keyRadiusDp)
        val borders = prefs.keyBorders
        val paper = prefs.style == "paper"
        val shadowDy = dp(if (paper) 0.6f else 1.2f)
        // pressed, a key goes down onto the board: a little smaller, a little lower, its shadow gone
        val pressInset = dp(1.1f)
        val pressDy = dp(0.7f)
        val tp = trackpad.value
        val la = labelsAlpha.value * (1f - 0.88f * tp)
        var animating = labelsAlpha.running || trackpad.running || spaceSwap.running || keyTapT.running
        if (spaceOffset.running) { spaceOffset.step(); animating = true }
        for (i in keys.indices) {
            val k = keys[i]
            val rect = rects[i]
            val press = pressAnim[k]
            val pv = press?.value ?: 0f
            if (press?.running == true) animating = true
            val armed = k.fn == "backspace" && bsArmed != null
            val style = if (armed) KeyStyle.ACTION else k.style
            val inset = pressInset * pv
            val sink = pressDy * pv
            val kr = tmpRect.apply { set(rect.left + inset, rect.top + inset + sink, rect.right - inset, rect.bottom - inset + sink) }
            var fill = when (style) {
                KeyStyle.NORMAL -> Colors.blend(t.surface, t.surfacePressed, pv)
                KeyStyle.FUNC, KeyStyle.SOFT -> Colors.blend(t.soft, t.softPressed, pv)
                KeyStyle.ACTION -> Colors.blend(t.accent, t.accentPressed, pv)
                KeyStyle.TINT -> Colors.blend(t.accentSoft, t.accent, pv * 0.3f)
            }
            // the trackpad: every key but space melts into the same soft surface
            val isSpace = k.fn == "space"
            if (tp > 0f && !isSpace) fill = Colors.blend(fill, t.soft, tp)
            if (style == KeyStyle.NORMAL && borders && (tp < 1f || isSpace)) {
                keyPaint.color = Colors.fade(t.shadow, (if (isSpace) 1f else 1f - tp) * (1f - pv))
                val sd = shadowDy * (1f - pv)
                c.drawRoundRect(kr.left, kr.top + sd, kr.right, kr.bottom + sd, r, r, keyPaint)
            }
            keyPaint.color = fill
            if (style == KeyStyle.NORMAL && pv < 0.01f && tp < 0.01f) {
                // at rest, a solid key shows its material; pressed or melting, the plain fill
                val sh = surfaceShader(t, rect.height())
                shaderMatrix.setTranslate(0f, rect.top); sh.setLocalMatrix(shaderMatrix)
                keyPaint.shader = sh
                c.drawRoundRect(kr, r, r, keyPaint)
                keyPaint.shader = null
                if (t.dark && !paper) {
                    keyPaint.color = Colors.alpha(android.graphics.Color.WHITE, 0x12)
                    c.drawRoundRect(kr.left + r * 0.6f, kr.top, kr.right - r * 0.6f, kr.top + dp(1f), dp(0.5f), dp(0.5f), keyPaint)
                }
            } else c.drawRoundRect(kr, r, r, keyPaint)
            if (paper && style == KeyStyle.NORMAL && tp < 0.01f) {
                // the edge of a sheet of paper: a hairline, warm, instead of a plastic shine
                keyPaint.style = android.graphics.Paint.Style.STROKE; keyPaint.strokeWidth = dp(0.75f)
                keyPaint.color = if (t.dark) Colors.alpha(0xECE5D8.toInt() or (0xFF shl 24), 0x0A) else Colors.alpha(0x5B4A36 or (0xFF shl 24), 0x16)
                c.drawRoundRect(kr, r, r, keyPaint)
                keyPaint.style = android.graphics.Paint.Style.FILL
            }
            val ink = when (style) {
                KeyStyle.ACTION -> t.onAccent
                KeyStyle.TINT -> t.accent
                KeyStyle.FUNC -> t.inkSoft
                else -> t.ink
            }
            if (isSpace) drawSpace(c, kr, k.label, tp, busy = g != null || pv > 0f) else drawKeyContent(c, k, kr, la, ink)
        }
        if (g != null) {
            c.restore()
            if (swap.running) {
                // the keyboard that left, on its way out the other side
                ghostPaint.alpha = (255 * (1f - sp)).toInt()
                c.drawBitmap(g, -swapDir * sp * travel, 0f, ghostPaint)
                animating = true
            } else { g.recycle(); ghost = null }
        }
        if (animating) postInvalidateOnAnimation()
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
        val a = (255 * labelAlpha).toInt()
        textPaint.color = ink
        textPaint.alpha = a
        iconPaint.color = ink
        iconPaint.alpha = a
        val fn = k.fn
        if (fn != null) {
            when {
                fn == "backspace" && bsArmed != null -> {
                    textPaint.textSize = sp(Ui.KEY_WORD)
                    textPaint.typeface = Ui.medium
                    drawText(c, if (bsArmed == "clear") "清空" else "撤销", cx, cy, textPaint)
                }
                fn == "shift" -> {
                    val st = listener?.shiftState() ?: 0
                    if (st > 0) { iconPaint.color = theme.accent; iconPaint.alpha = a }
                    Icons.draw(c, "shift", cx, cy, dp(Ui.KEY_ICON), iconPaint, filled = st > 0, t = tapOf(k))
                    // caps lock: a bar under the arrow
                    if (st == 2) c.drawRoundRect(cx - dp(6f), cy + dp(12f), cx + dp(6f), cy + dp(13.6f), dp(1f), dp(1f), iconPaint)
                }
                fn == "lock" -> {
                    if (lockOn) { iconPaint.color = theme.accent; iconPaint.alpha = a }
                    Icons.draw(c, if (lockOn) "lock" else "unlock", cx, cy, dp(Ui.KEY_ICON), iconPaint)
                }
                k.label != null || !Icons.all.containsKey(fn) -> {
                    val tint = k.style == KeyStyle.TINT
                    textPaint.textSize = sp(Ui.KEY_WORD * prefs.labelScale)
                    textPaint.typeface = if (tint) Ui.medium else Ui.regular
                    val label = k.label ?: fn
                    fitText(label, rect.width() - dp(8f), textPaint)
                    drawText(c, label, cx, cy, textPaint)
                }
                else -> Icons.draw(c, fn, cx, cy, dp(Ui.KEY_ICON), iconPaint, t = tapOf(k))
            }
            return
        }
        val label = displayLabel(k)
        val single = label.codePointCount(0, label.length) <= 1
        val mode = prefs.hintMode
        // 只第一排数字: a key's corner shows its digit only
        val hints = mode == "all" || (mode == "digits" && k.flicks[Dir.UP]?.label?.singleOrNull()?.isDigit() == true)
        textPaint.typeface = Ui.regular
        textPaint.textSize = sp(
            when {
                mathMode && label.length > 4 -> 12.5f
                mathMode && label.length > 2 -> 15f
                mathMode && label.singleOrNull()?.isDigit() == true -> 21f
                mathMode -> 18f
                single -> Ui.KEY
                else -> 16f
            } * prefs.labelScale
        )
        fitText(label, rect.width() - dp(6f), textPaint)
        if (!mathMode && single && label.isNotEmpty() && !label[0].isLetterOrDigit()) drawByBounds(c, label, cx, cy, textPaint)
        else drawText(c, label, cx, cy + if (!hints || k.flicks[Dir.UP] == null) 0f else dp(2f), textPaint)

        if (!hints) return
        hintPaint.color = if (k.style == KeyStyle.ACTION) theme.onAccent else theme.hint
        hintPaint.alpha = a
        hintPaint.textSize = sp(Ui.HINT * prefs.hintScale)
        hintPaint.typeface = Ui.regular
        val fl = k.flicks
        if (prefs.showAllHints && mode == "all") {
            fl[Dir.UP]?.let { drawText(c, hintLabel(it.label), cx, rect.top + hintPaint.textSize * 0.75f, hintPaint) }
            fl[Dir.DOWN]?.let { drawText(c, hintLabel(it.label), cx, rect.bottom - hintPaint.textSize * 0.75f, hintPaint) }
            fl[Dir.LEFT]?.let { drawText(c, hintLabel(it.label), rect.left + hintPaint.measureText(it.label) / 2 + dp(4f), cy, hintPaint) }
            fl[Dir.RIGHT]?.let { drawText(c, hintLabel(it.label), rect.right - hintPaint.measureText(it.label) / 2 - dp(4f), cy, hintPaint) }
        } else fl[Dir.UP]?.let {
            val h = hintLabel(it.label)
            val top = rect.top + dp(4f) + hintPaint.textSize * 0.5f
            if (h.codePointCount(0, h.length) == 1 && !h[0].isLetterOrDigit()) {
                // punctuation: center the ink, not the glyph box (、 and ， sit low in theirs)
                hintPaint.getTextBounds(h, 0, h.length, bounds)
                val ink = max(bounds.width().toFloat(), hintPaint.textSize * 0.45f)
                drawByBounds(c, h, rect.right - ink / 2 - dp(6f), top, hintPaint)
            } else {
                val w = hintPaint.measureText(h)
                // too long for the corner (arcsin over sin): a dot says there is more
                if (w > rect.width() * 0.5f) c.drawCircle(rect.right - dp(7f), rect.top + dp(7f), dp(1.8f), hintPaint)
                else drawText(c, h, rect.right - w / 2 - dp(6f), top, hintPaint)
            }
        }
    }

    /** Shrinks [p]'s text size so [s] fits in [maxW]. */
    private fun fitText(s: String, maxW: Float, p: Paint) {
        val tw = p.measureText(s)
        if (tw > maxW && tw > 0f) p.textSize *= maxW / tw
    }

    /** Short form of a hint so it stays in the key's corner (—— is shown as one dash). */
    private fun hintLabel(s: String) = if (s == "——") "—" else s

    /** Centers the ink of [s] (not its glyph box) on (x, y): punctuation sits off-center in its box. */
    private fun drawByBounds(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        p.getTextBounds(s, 0, s.length, bounds)
        // paint is center-aligned, bounds are relative to a left-aligned origin
        c.drawText(s, x + p.measureText(s) / 2 - bounds.exactCenterX(), y - bounds.exactCenterY(), p)
    }

    private fun drawSpace(c: Canvas, rect: RectF, fixedLabel: String?, tp: Float, busy: Boolean = false) {
        var shark = false
        textPaint.textSize = sp(13.5f * prefs.labelScale)
        textPaint.typeface = Ui.regular
        c.save()
        c.clipRect(rect)
        val cx = rect.centerX(); val cy = rect.centerY()
        if (tp > 0f) {
            // the trackpad: where the caret goes, said once, quietly
            textPaint.color = theme.accent
            textPaint.alpha = (255 * tp).toInt()
            iconPaint.color = theme.accent
            iconPaint.alpha = textPaint.alpha
            val label = "拖动移动光标"
            val half = textPaint.measureText(label) / 2
            drawText(c, label, cx, cy, textPaint)
            Icons.draw(c, "left", cx - half - dp(14f), cy, dp(16f), iconPaint)
            Icons.draw(c, "right", cx + half + dp(14f), cy, dp(16f), iconPaint)
        }
        if (tp < 1f) {
            val fade = 1f - tp
            textPaint.color = theme.hint
            val label = fixedLabel ?: listener?.spaceLabel() ?: ""
            val off = spaceOffset.value
            val p = spaceSwap.value
            val old = oldSpaceLabel
            if (old != null && p < 1f) {
                textPaint.alpha = (255 * (1 - p) * fade).toInt()
                drawText(c, old, cx + spaceSwapDir * p * rect.width() * 0.5f, cy, textPaint)
                textPaint.alpha = (255 * p * fade).toInt()
                drawText(c, label, cx - spaceSwapDir * (1 - p) * rect.width() * 0.5f, cy, textPaint)
            } else {
                textPaint.alpha = (255 * fade * (1 - min(1f, abs(off) / rect.width()) * 0.5f)).toInt()
                if (fixedLabel == null && listener?.kayOnSpace() == true) {
                    shark = true
                    prefs.kayTune().let { if (it != SharkLife.tune) SharkLife.tune = it }
                    val now = Motion.now()
                    val geo = sharkGeometry(rect, label, off)
                    // a nibble nudges the name
                    val push = SharkLife.push(now) * Shark.cellFor(geo.size)
                    drawText(c, label, geo.labelX + push, cy, textPaint)
                    // calm, it lives on its own small layer over the key, so its frames repaint the
                    // space key alone; while the keyboard itself moves, it is drawn here, with it
                    val inline = busy || off != 0f || parent !is android.widget.FrameLayout
                    sharkAlpha = textPaint.alpha
                    sharkLabel = label
                    spaceRect.set(rect)
                    if (inline) {
                        invalidateIn(drawShark(c, rect, label, off, textPaint.alpha, now))
                        sharkLayer(show = false)
                    } else sharkLayer(show = true)
                } else drawText(c, label, cx + off, cy, textPaint)
                if (abs(off) > dp(8f)) {
                    // which way the language list turns
                    iconPaint.color = theme.accent
                    iconPaint.alpha = (255 * fade * min(1f, abs(off) / dp(40f))).toInt()
                    Icons.draw(c, if (off > 0) "left" else "right", if (off > 0) rect.left + dp(18f) else rect.right - dp(18f), cy, dp(16f), iconPaint)
                }
            }
        }
        c.restore()
        if (!shark) sharkLayer(show = false)
    }

    // ---- 小K on the space key ----

    private class SharkGeo(val size: Float, val sharkX: Float, val labelX: Float)

    private fun sharkGeometry(rect: RectF, label: String, off: Float): SharkGeo {
        val face = dp(17f) * prefs.labelScale
        val gap = dp(5f)
        val cx = rect.centerX() + off
        val start = cx - (face + gap + textPaint.measureText(label)) / 2
        return SharkGeo(face * 0.9f, start + face / 2, cx + (face + gap) / 2)
    }

    /** The shark and what it says under the name; the ms until its next change (0: keep going). */
    private fun drawShark(c: Canvas, rect: RectF, label: String, off: Float, alpha: Int, now: Long): Long {
        textPaint.textSize = sp(13.5f * prefs.labelScale)
        textPaint.typeface = Ui.regular
        val geo = sharkGeometry(rect, label, off)
        val wait = Shark.drawLive(c, geo.sharkX, rect.centerY(), geo.size, alpha = alpha, still = true)
        val words = SharkLife.saying(now) ?: return wait
        val show = SharkLife.sayingShown(now)
        hintPaint.color = theme.hint
        hintPaint.typeface = Ui.regular
        hintPaint.textSize = sp(Ui.HINT * prefs.hintScale * prefs.kaySaySize)
        hintPaint.alpha = (alpha * 0.7f * show).toInt()
        fitText(words, rect.width() - dp(12f), hintPaint)
        drawText(c, words, rect.centerX() + off, rect.bottom - dp(4f) - hintPaint.textSize * 0.6f, hintPaint)
        // fading in or out: a few steps, not every frame
        return if (show < 1f) (if (wait == 0L) 0L else minOf(wait, SharkLife.FADE_STEP_MS)) else wait
    }

    private val spaceRect = RectF()
    private var sharkAlpha = 255
    private var sharkLabel = ""
    private var lastPush = 0

    /**
     * The shark's own layer: a view the size of the space key over it, in the keyboard's parent.
     * Its frames invalidate it alone; the keyboard repaints only when a nibble nudges the name.
     */
    private inner class SharkLayer : View(context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent) = false
        private val tick = Runnable { invalidate() }
        override fun onDraw(c: Canvas) {
            val now = Motion.now()
            c.save(); c.translate(-spaceRect.left, -spaceRect.top)
            val wait = drawShark(c, spaceRect, sharkLabel, 0f, sharkAlpha, now)
            c.restore()
            removeCallbacks(tick)
            if (wait == 0L) postInvalidateOnAnimation() else postDelayed(tick, wait)
            val push = SharkLife.push(now)
            if (push != lastPush) { lastPush = push; this@KeyboardView.invalidate() }
        }
    }
    private var layer: SharkLayer? = null

    private fun sharkLayer(show: Boolean) {
        val host = parent as? android.widget.FrameLayout
        if (!show || host == null || spaceRect.isEmpty) { layer?.let { if (it.visibility != GONE) it.visibility = GONE }; return }
        val l = layer ?: SharkLayer().also { layer = it }
        val w = spaceRect.width().toInt() + 1; val h = spaceRect.height().toInt() + 1
        if (l.parent !== host) {
            // the hierarchy is not changed while drawing
            handler.post {
                if (l.parent !== parent && parent is android.widget.FrameLayout) {
                    (l.parent as? android.view.ViewGroup)?.removeView(l)
                    (parent as android.widget.FrameLayout).addView(l, android.widget.FrameLayout.LayoutParams(w, h))
                    invalidate()
                }
            }
            return
        }
        val lp = l.layoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; handler.post { l.layoutParams = lp } }
        val tx = left + translationX + spaceRect.left; val ty = top + translationY + spaceRect.top
        if (l.translationX != tx) l.translationX = tx
        if (l.translationY != ty) l.translationY = ty
        if (l.alpha != alpha) l.alpha = alpha
        if (l.visibility != VISIBLE) l.visibility = VISIBLE
        l.invalidate()
    }

    // ---- touch --------------------------------------------------------------------------

    private enum class Mode { NONE, PRESS, POPUP, CURSOR, SPACE_SWIPE, BS_GESTURE, BS_REPEAT, CANCELLED }

    private companion object {
        /** How long a function key's icon motion runs. */
        const val KEY_TAP_MS = 460L
        /** A drag that takes longer than this to go far is a change of mind, not a flick. */
        const val SLOW_DRAG_MS = 450L
    }

    private var downTime = 0L
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

    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            // only while the key is still held on an attached view: deleting can rebuild the panel,
            // and a view that is taken off and put back never hears the finger lift
            if (mode != Mode.BS_REPEAT || active == null || !isAttachedToWindow) return
            listener?.onBackspaceRepeat()
            if (mode == Mode.BS_REPEAT && isAttachedToWindow) handler.postDelayed(this, prefs.backspaceRepeatMs.toLong())
        }
    }

    /** A letter key, whose taps are learned and read against where they land. */
    private fun learnable(k: Key) = k.fn == null && k.main?.text?.let { it.length == 1 && it[0].isLetter() } == true

    private val center = FloatArray(2)

    private fun keyAt(x: Float, y: Float): Int {
        for (i in rects.indices) if (rects[i].contains(x, y)) return adapted(i, x, y)
        // fall back to the nearest key so taps in the gaps still count
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in rects.indices) {
            val r = rects[i]
            val dx = max(0f, max(r.left - x, x - r.right))
            val dy = max(0f, max(r.top - y, y - r.bottom))
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return if (best >= 0 && abs(rects[best].centerY() - y) < keyH) best else -1
    }

    /**
     * The key a tap inside [hit] is read as: of [hit] and its neighbours in the same row, the one
     * whose learned center is nearest across, in key widths. A neighbour only wins near the shared
     * edge (within [TouchModel.MAX_SHIFT] of a key width), and before anything is learned every
     * center is the key's own, so taps are read exactly by the key shapes.
     */
    private fun adapted(hit: Int, x: Float, y: Float): Int {
        val model = touch ?: return hit
        if (!learnable(keys[hit])) return hit
        val row = rects[hit]
        var best = hit; var bd = Float.MAX_VALUE
        for (i in rects.indices) {
            val r = rects[i]
            if (i != hit) {
                if (!learnable(keys[i]) || abs(r.centerY() - row.centerY()) > row.height() / 2) continue
                val m = r.width() * TouchModel.MAX_SHIFT
                if (x < r.left - m || x > r.right + m) continue
            }
            model.center(keys[i].main!!.text, r, center)
            val d = abs(x - center[0]) / r.width()
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // fast typing: a second finger finishes the first key as a tap (and ends a repeat)
                if (mode == Mode.PRESS) finish(commit = true) else if (mode != Mode.NONE) finish(commit = false)
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
        val hit = keyAt(x, y)
        if (hit < 0) return
        val k = keys[hit]
        touch?.let { m ->
            val now = SystemClock.uptimeMillis()
            if (!learnTaps) {} else if (k.fn == "backspace") m.undone(now) else if (learnable(k)) m.tapped(k.main!!.text, rects[hit], x, y, now)
        }
        pointerId = e.getPointerId(index)
        active = k
        activeRect = rects[hit]
        downX = x; downY = y; lastX = x; lastY = y
        downTime = SystemClock.uptimeMillis()
        flick = null
        mode = Mode.PRESS
        pressAnim.getOrPut(k) { Tween() }.animateTo(1f, dur(Motion.PRESS))
        invalidate()
        listener?.onPress(k)
        if (k.main != null && prefs.keyPreview) overlay?.showPreview(previewRect(rects[hit]), displayLabel(k), null)
        val delay = if (k.fn == "backspace") 420L else prefs.longPressMs.toLong()
        handler.postDelayed(longPressRunnable, delay)
    }

    private fun previewRect(r: RectF): RectF {
        locateIn(overlay)
        val w = max(r.width() * 1.12f, dp(48f))
        val h = keyH * 1.15f
        val cx = r.centerX() + layerOffset[0]
        val top = r.top + layerOffset[1] - h - dp(6f)
        return RectF(cx - w / 2, top, cx + w / 2, top + h)
    }

    private fun move(x: Float, y: Float) {
        val k = active ?: return
        val dx = x - downX
        val dy = y - downY
        val threshold = dp(prefs.flickDp)
        when (mode) {
            Mode.POPUP -> {
                locateIn(overlay)
                overlay?.moveSelection(x + layerOffset[0], y + layerOffset[1])
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
                val prev = spaceOffset.value
                val next = (dx * 0.7f).coerceIn(-w * 0.45f, w * 0.45f)
                if (abs(prev) < dp(24f) && abs(next) >= dp(24f)) listener?.onFeedback("gesture")
                spaceOffset.drag(next)
                invalidate()
            }
            Mode.BS_GESTURE -> {
                val armed = when {
                    dy < -threshold && abs(dy) > abs(dx) -> "clear"
                    dy > threshold && abs(dy) > abs(dx) -> "undo"
                    else -> null
                }
                if (armed != bsArmed) {
                    bsArmed = armed
                    if (armed != null) listener?.onFeedback("gesture")
                    invalidate()
                }
            }
            Mode.BS_REPEAT, Mode.CANCELLED, Mode.NONE -> {}
            Mode.PRESS -> {
                // 误触: a flick is quick, however long it is. Only a slow drag far away (the finger
                // wandering off to say "never mind") cancels the key.
                val w = activeRect?.width() ?: threshold
                val held = SystemClock.uptimeMillis() - downTime
                if (prefs.dragCancel && k.fn != "space" && k.fn != "backspace" && held > SLOW_DRAG_MS &&
                    hypot(dx, dy) > max(threshold * 4f, w * 2.2f)) {
                    mode = Mode.CANCELLED
                    flick = null
                    overlay?.hidePreview()
                    pressAnim[k]?.animateTo(0f, dur(Motion.RELEASE))
                    listener?.onFeedback("cancel")
                    invalidate()
                    return
                }
                if (hypot(dx, dy) < threshold) {
                    if (flick != null) {
                        flick = null
                        if (k.main != null && prefs.keyPreview) overlay?.showPreview(previewRect(activeRect!!), displayLabel(k), null)
                    }
                } else {
                    handler.removeCallbacks(longPressRunnable)
                    when {
                        k.fn == "space" && abs(dx) > abs(dy) -> when (spaceSwipeOverride ?: prefs.spaceSwipe) {
                            "language" -> { mode = Mode.SPACE_SWIPE; move(x, y); return }
                            "cursor" -> { enterCursor(); lastX = downX; move(x, y); return }
                        }
                        k.fn == "backspace" && prefs.backspaceSwipe -> { mode = Mode.BS_GESTURE; move(x, y); return }
                        k.main != null -> {
                            val d = if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT) else (if (dy > 0) Dir.DOWN else Dir.UP)
                            if (d != flick) {
                                flick = d
                                val out = k.flicks[d]
                                if (prefs.keyPreview) overlay?.showPreview(previewRect(activeRect!!), out?.label ?: displayLabel(k), if (out != null) d else null)
                            }
                        }
                    }
                }
            }
        }
        lastX = x; lastY = y
    }

    private fun enterCursor() {
        mode = Mode.CURSOR
        cursorMode = true
        cursorAccX = 0f; cursorAccY = 0f
        overlay?.hidePreview()
        trackpad.animateTo(1f, dur(Motion.SWAP), Motion.ENTER)
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
                handler.postDelayed(repeatRunnable, prefs.backspaceRepeatMs.toLong())
            }
            else -> {
                val opts = listener?.longOptionRows(k).orEmpty().filter { it.isNotEmpty() }
                if (opts.isEmpty()) return
                mode = Mode.POPUP
                overlay?.hidePreview()
                locateIn(overlay)
                overlay?.showOptionRows(onLayer(activeRect!!), keyH, opts, lastX + layerOffset[0])
                listener?.onFeedback("popup")
            }
        }
    }

    private fun finish(commit: Boolean) {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        val k = active
        if (k != null) pressAnim[k]?.animateTo(0f, dur(Motion.RELEASE), Motion.ENTER)
        overlay?.hidePreview()
        // held much longer than a tap without anything to pick: treated as a change of mind
        val heldTooLong = prefs.holdCancelMs > 0 && SystemClock.uptimeMillis() - downTime > prefs.holdCancelMs
        if (k != null && commit) when (mode) {
            Mode.PRESS -> if (!heldTooLong || flick != null) {
                val fl = flick
                val main = k.main
                if (main != null) listener?.onOutput(k, if (fl != null) k.flicks[fl] ?: main else main)
                else { tapped(k); listener?.onFunction(k) }
            }
            Mode.POPUP -> overlay?.selected()?.let { listener?.onOption(k, it) }
            Mode.SPACE_SWIPE -> {
                val off = spaceOffset.value
                if (abs(off) > dp(24f)) {
                    val dir = if (off < 0) 1 else -1
                    oldSpaceLabel = listener?.spaceLabel()
                    val nl = listener?.onSwitchLanguage(dir)
                    if (nl != null) {
                        spaceSwapDir = if (off < 0) -1 else 1
                        spaceSwap.set(0f)
                        spaceSwap.animateTo(1f, dur(240), Motion.MOVE)
                        spaceOffset.snap(0f)
                    }
                }
            }
            Mode.BS_GESTURE -> bsArmed?.let { listener?.onBackspaceGesture(it) }
            else -> {}
        }
        bsArmed = null
        if (mode == Mode.POPUP) overlay?.hideOptions()
        // the label springs home from wherever the finger left it
        spaceOffset.enabled = Motion.on(prefs)
        if (spaceOffset.value != 0f) spaceOffset.animateTo(0f)
        if (cursorMode) {
            cursorMode = false
            trackpad.animateTo(0f, dur(Motion.RELEASE), Motion.ENTER)
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
        // gone with the keyboard (removed from the parent along with it, or hidden till it is back)
        layer?.visibility = GONE
        touch?.save()
        ghost?.recycle(); ghost = null
        if (mode != Mode.NONE) finish(commit = false)
        handler.removeCallbacksAndMessages(null)
    }
}
