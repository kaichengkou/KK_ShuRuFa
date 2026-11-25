package com.kk.shurufa.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import com.kk.shurufa.Prefs

/**
 * 小K's page, where the keyboard was when the 小K key is tapped: 小K itself, large and alive, its
 * line of the day in a bubble, and the three things it does (改写, 翻译, 聊天) as large cards.
 * Picking one brings the keyboard back with 小K's strip above it.
 *
 * It comes in in a cascade: 小K rises first, the bubble opens, the cards follow one after
 * another; a card pressed sinks a little under the finger.
 */
@SuppressLint("ViewConstructor")
class KayHome(context: Context, prefs: Prefs) : KbView(context, prefs) {
    /** "rewrite", "translate", "chat", "all" (the conversation), "setup". */
    var onChoose: ((String) -> Unit)? = null
    var onFeedback: (() -> Unit)? = null
    var onPat: (() -> Unit)? = null

    var kayName = "小K"
    var line = ""
    /** An AI service is set up (翻译 works without one). */
    var aiReady = true
    var provider = ""
    var place: String? = null
    var placeOk: Boolean? = null
    /** Turns in the conversation so far (a way back to it when there are any). */
    var talks = 0

    private class Card(val id: String, val title: String, val sub: String, val icon: String)
    private val cards get() = listOf(
        Card("rewrite", "改写", if (aiReady) "润色 · 纠错 · 正式…" else "先设置 AI 服务", "ai"),
        Card("translate", "翻译", "中 · 英 · 俄", "translate"),
        Card("chat", "聊天", if (aiReady) "跟${kayName}说说话" else "先设置 AI 服务", "emoji"),
    )

    private val appear = Tween(1f)
    private val pressT = Tween(0f)
    private var pressed: String? = null
    private val hits = Hits()
    private val r = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** The page comes into view: the cascade plays. */
    fun entered() {
        if (Motion.on(prefs)) { appear.set(0f); appear.animateTo(1f, dur(520), Motion.LINEAR) } else appear.set(1f)
        invalidate()
    }

    /** How far along item [i] of the cascade is (0 to 1), eased. */
    private fun stage(i: Int): Float {
        val p = ((appear.value - i * 0.12f) / 0.55f).coerceIn(0f, 1f)
        return Motion.ENTER.getInterpolation(p)
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.background)
        hits.clear()
        var again = appear.running || pressT.running
        val pad = dp(Ui.EDGE + 2f)
        val w = width.toFloat(); val h = height.toFloat()
        // the top line, quiet: where the internet sees us, and the model
        tp.textSize = sp(Ui.SMALL - 1f); tp.typeface = Ui.regular; tp.color = t.hint
        val top = dp(18f)
        val info = listOfNotNull(place, provider.takeIf { it.isNotEmpty() }).joinToString("  ·  ")
        if (info.isNotEmpty()) {
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(info, w - pad, top, tp)
            if (place != null) {
                val dx = w - pad - tp.measureText(info) - dp(7f)
                bg.color = when (placeOk) { true -> 0xFF4CAF7A.toInt(); false -> 0xFFE0A33A.toInt(); null -> t.hint }
                c.drawCircle(dx, top - dp(4f), dp(2.6f), bg)
            }
        }
        if (talks > 0) {
            tp.textAlign = Paint.Align.LEFT; tp.color = t.accent
            val s = "对话 $talks 句 ›"
            c.drawText(s, pad, top, tp)
            hits.add("all", pad - dp(6f), top - dp(16f), pad + tp.measureText(s) + dp(6f), top + dp(8f))
        }
        // the cards along the bottom
        val gap = dp(8f)
        val cardH = minOf(dp(92f), h * 0.36f)
        val cardW = (w - 2 * pad - 2 * gap) / 3
        val cardT = h - dp(12f) - cardH
        // 小K and its bubble in the space above them
        val s0 = stage(0)
        // 小K as large as the room above the cards allows, its bubble under it
        val face = minOf(dp(100f), cardT - dp(26f) - dp(50f))
        val sharkCy = dp(24f) + face * 0.5f + (1 - s0) * dp(18f)
        val wait = Shark.drawLive(c, w / 2, sharkCy, face, alpha = (255 * s0).toInt())
        if (wait == 0L) again = true else if (!again) invalidateIn(wait)
        hits.add("pat", w / 2 - face * 0.7f, sharkCy - face * 0.6f, w / 2 + face * 0.7f, sharkCy + face * 0.6f)
        val s1 = stage(1)
        if (line.isNotEmpty() && s1 > 0f) {
            tp.textAlign = Paint.Align.LEFT; tp.textSize = sp(Ui.BODY); tp.typeface = Ui.regular; tp.color = t.ink; tp.alpha = (255 * s1).toInt()
            val maxW = (w - 2 * pad - dp(24f)).toInt()
            val lay = StaticLayout.Builder.obtain(line, 0, line.length, tp, maxW).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            var lw = 0f
            for (i in 0 until lay.lineCount) lw = maxOf(lw, lay.getLineWidth(i))
            val bw = lw + dp(24f); val bh = lay.height + dp(14f)
            val by = sharkCy + face * 0.5f + dp(8f)
            val scale = 0.9f + 0.1f * s1
            c.save(); c.scale(scale, scale, w / 2, by)
            bg.color = t.surface; bg.alpha = (255 * s1).toInt()
            r.set(w / 2 - bw / 2, by, w / 2 + bw / 2, by + bh)
            c.drawRoundRect(r, dp(14f), dp(14f), bg)
            // its tail, toward 小K
            val path = android.graphics.Path().apply { moveTo(w / 2 - dp(6f), by + 0.5f); lineTo(w / 2, by - dp(6f)); lineTo(w / 2 + dp(6f), by + 0.5f); close() }
            c.drawPath(path, bg)
            c.translate(w / 2 - maxW / 2f, by + dp(7f)); lay.draw(c)
            c.restore()
            tp.alpha = 255
        }
        for ((i, card) in cards.withIndex()) {
            val s = stage(2 + i)
            if (s <= 0f) continue
            val l = pad + i * (cardW + gap)
            r.set(l, cardT, l + cardW, cardT + cardH)
            val press = if (pressed == card.id) pressT.value else 0f
            val sc = (0.94f + 0.06f * s) * (1f - 0.04f * press)
            c.save()
            c.translate(0f, (1 - s) * dp(16f))
            c.scale(sc, sc, r.centerX(), r.centerY())
            bg.color = if (pressed == card.id) Colors.blend(t.surface, t.accentSoft, 0.5f * press) else t.surface
            bg.alpha = (255 * s).toInt()
            val rad = Ui.radiusLarge(dp(prefs.keyRadiusDp), dp(1f))
            c.drawRoundRect(r, rad, rad, bg)
            val dim = !aiReady && card.id != "translate"
            ip.color = Colors.fade(if (dim) t.hint else t.accent, s); ip.strokeWidth = dp(1.7f)
            Icons.draw(c, card.icon, r.centerX(), r.top + cardH * 0.32f, dp(24f), ip)
            tp.textAlign = Paint.Align.CENTER; tp.textSize = sp(Ui.BODY + 1f); tp.typeface = Ui.medium
            tp.color = Colors.fade(if (dim) t.inkSoft else t.ink, s)
            c.drawText(card.title, r.centerX(), r.top + cardH * 0.64f, tp)
            tp.textSize = sp(Ui.SMALL - 1f); tp.typeface = Ui.regular; tp.color = Colors.fade(t.hint, s)
            val sub = TextUtils.ellipsize(card.sub, tp, cardW - dp(10f), TextUtils.TruncateAt.END).toString()
            c.drawText(sub, r.centerX(), r.top + cardH * 0.84f, tp)
            c.restore()
            hits.add(card.id, r)
        }
        if (again) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.at(e.x, e.y)
                if (pressed != null) {
                    onFeedback?.invoke()
                    if (pressed != "pat" && pressed != "all") { pressT.set(0f); pressT.animateTo(1f, dur(90), Motion.ENTER) }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                if (p != null && hits.at(e.x, e.y) == p) {
                    when {
                        p == "pat" -> onPat?.invoke()
                        !aiReady && (p == "rewrite" || p == "chat") -> onChoose?.invoke("setup")
                        else -> onChoose?.invoke(p)
                    }
                }
                if (Motion.on(prefs)) pressT.animateTo(0f, dur(160), Motion.ENTER) else pressT.set(0f)
                postDelayed({ pressed = null; invalidate() }, dur(160))
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
