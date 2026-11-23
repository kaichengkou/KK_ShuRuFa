package com.kk.shurufa.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import java.time.LocalDate

private typealias P = Shark.Pose
private typealias F = Shark.Face

/**
 * 小K, the little shark: a pixel sprite whose faces are kaomoji — (・ω・) (^ω^) (>ω<) (ﾟдﾟ)!
 * (╥ω╥) (☆ω☆) (￣▽￣) (˘ω˘)zZ (/ω＼) — and whose few moves are made of whole pixels. Its own
 * colours (shark blue, white belly) never follow the theme: it is a character, not a control.
 *
 * The sprite is 14 × 13 on a 18 × 17 grid (room above for !, z, bubbles and what it wears on a
 * festival or a solar term), drawn in whole pixels so it stays crisp at any size.
 */
object Shark {
    enum class Face(val kaomoji: String) {
        CALM("(・ω・)"), BLINK("(－ω－)"), HAPPY("(^ω^)"), PAT("(>ω<)"), SURPRISED("(ﾟдﾟ)!"), SAD("(╥ω╥)"),
        STAR("(☆ω☆)"), SMUG("(￣▽￣)"), SLEEP("(˘ω˘)zZ"), COVER("(/ω＼)"), YAWN("(´ρ`)"), LOOKUP("(・ω・)?"),
        CHOMP("(>▽<)嗷"), BITE("(>ω<)"), DOWN("(・ω・)…"), SING("(ˊoˋ)♪"), COOL("(⌐■_■)")
    }
    enum class Extra { NONE, BANG, HEART, Z, BUBBLES }
    /** What it holds or has on for a longer act. */
    enum class Prop { NONE, LAPTOP, NOTES, SHADES, FISH, DUMBBELL }

    /** One frame: a face and how the body is held. */
    data class Pose(
        val face: Face = Face.CALM, val dy: Int = 0, val squash: Boolean = false, val wave: Boolean = false,
        val blush: Boolean = false, val extra: Extra = Extra.NONE, val phase: Int = 0, val flick: Boolean = false,
        /** Where the eyes look (−1 left, 1 right), how far it has moved (whole pixels), facing the other way. */
        val look: Int = 0, val dx: Int = 0, val flip: Boolean = false,
        val prop: Prop = Prop.NONE, val waveLeft: Boolean = false,
    )

    private const val BODY = 0xFF7096BA.toInt()
    private const val DARK = 0xFF54769C.toInt()
    private const val BELLY = 0xFFFAF9F4.toInt()
    private const val EYE = 0xFF1E232B.toInt()
    private const val SHINE = 0xFFFFFFFF.toInt()
    private const val BLUSH = 0xFFF6A0B0.toInt()
    private const val TEAR = 0xFF78BEF0.toInt()
    private const val STAR = 0xFFFFE27A.toInt()
    private const val RED = 0xFFE6505A.toInt()
    private const val ZZZ = 0xFFAAB4C4.toInt()
    private const val BUBBLE = 0xFFBFE3F5.toInt()

    private val SPRITE = arrayOf(
        "......h.......",
        ".....hh.......",
        "...HHHHHHHH...",
        "..HHHHHHHHHH..",
        ".HHHHHHHHHHHH.",
        ".HHHHHHHHHHHH.",
        "HHHHHHHHHHHHHH",
        "hHHWWWWWWWWHHh",
        "hhWWWWWWWWWWhh",
        ".HWWWWWWWWWWH.",
        ".HHWWWWWWWWHH.",
        "..HHHHHHHHHHhh",
        "............h.",
    )
    private const val OX = 2
    private const val OY = 4
    const val GRID_W = 18
    const val GRID_H = 17

    private val paint = Paint()
    private var cell = 1f
    private var left = 0f
    private var top = 0f
    private lateinit var canvas: Canvas
    private var alpha = 255

    private var shiftX = 0
    private var mirror = false

    private fun px(x0: Int, y: Int, color: Int) {
        val x = (if (mirror) 2 * OX + 13 - x0 else x0) + shiftX
        paint.color = color; paint.alpha = (Color8.alpha(color) * alpha / 255)
        canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, paint)
    }

    /** The size of one pixel of 小K when drawn [size] tall. */
    fun cellFor(size: Float) = maxOf(1f, kotlin.math.floor(size / 13f))

    /**
     * Draws [pose] centred at ([cx], [cy]) at most [size] tall (in whole pixels, so it stays crisp),
     * [alpha] for fading with what it sits on; [wear] is what it has on today.
     */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, pose: Pose, alpha: Int = 255, wear: Wear? = wearToday()) {
        canvas = c; this.alpha = alpha
        // [size] is the body's height (13 rows); what goes above may reach past it
        cell = maxOf(1f, kotlin.math.floor(size / 13f))
        left = kotlin.math.round(cx - cell * GRID_W / 2); top = kotlin.math.round(cy - cell * 10.5f)
        shiftX = pose.dx; mirror = pose.flip
        val dy = pose.dy + if (pose.squash) 1 else 0
        // the body: squashed, one row of the middle is left out
        var row = 0
        for (y in SPRITE.indices) {
            if (pose.squash && y == 6) continue
            val line = SPRITE[y]
            for (x in line.indices) {
                var ch = line[x]
                if (pose.flick && y <= 1) ch = if (y == 0 && x == 6) '.' else if (y == 0 && x == 7) 'h' else ch
                if (pose.face == Face.COVER && (y == 7 || y == 8) && (x == 0 || x == 13)) ch = if (y == 7) 'H' else '.'
                when (ch) {
                    'H' -> px(x + OX, row + OY + dy - (if (pose.squash) 0 else 0), BODY)
                    'h' -> px(x + OX, row + OY + dy, DARK)
                    'W' -> px(x + OX, row + OY + dy, BELLY)
                }
            }
            row++
        }
        val sq = if (pose.squash) -1 else 0
        fun at(x: Int, y: Int, color: Int) = px(x + OX, y + OY + dy + (if (y >= 7) sq else 0), color)
        // the face
        val eyes = intArrayOf(4, 9)
        for ((i, ex0) in eyes.withIndex()) {
            val side = if (i == 0) -1 else 1
            val ex = ex0 + pose.look
            val ey = 5
            when (pose.face) {
                Face.CALM, Face.COOL -> { at(ex, ey, EYE); at(ex, ey - 1, EYE) }
                Face.DOWN -> { at(ex, ey + 1, EYE); at(ex, ey, EYE) }
                Face.SING -> { at(ex - 1, ey, EYE); at(ex, ey - 1, EYE); at(ex + 1, ey, EYE) }
                Face.LOOKUP -> { at(ex, ey - 1, EYE); at(ex, ey - 2, EYE) }
                Face.BLINK, Face.SLEEP -> { at(ex - 1, ey, EYE); at(ex, ey + (if (pose.face == Face.SLEEP) 1 else 0), EYE); at(ex + 1, ey, EYE) }
                Face.HAPPY -> { at(ex - 1, ey, EYE); at(ex, ey - 1, EYE); at(ex + 1, ey, EYE) }
                Face.PAT, Face.CHOMP, Face.BITE -> { at(ex - side, ey - 1, EYE); at(ex, ey, EYE); at(ex - side, ey + 1, EYE) }
                Face.SURPRISED -> { at(ex, ey, EYE); at(ex + side, ey, EYE); at(ex, ey + 1, EYE); at(ex + side, ey + 1, EYE); at(ex, ey - 1, SHINE); at(ex + side, ey - 1, EYE) }
                Face.SAD -> { at(ex, ey - 1, EYE); at(ex, ey, EYE); at(ex, ey + 1, TEAR); at(ex, ey + 2, TEAR) }
                Face.STAR -> { at(ex, ey, STAR); at(ex - 1, ey, STAR); at(ex + 1, ey, STAR); at(ex, ey - 1, STAR); at(ex, ey + 1, STAR) }
                Face.SMUG -> { at(ex - 1, ey - 1, EYE); at(ex, ey - 1, EYE); at(ex + 1, ey - 1, EYE); at(ex, ey, EYE) }
                Face.YAWN -> { at(ex - 1, ey, EYE); at(ex, ey, EYE); at(ex + 1, ey, EYE) }
                Face.COVER -> {
                    // eyes squeezed shut, both fins up beside the face: 不看不看
                    at(ex - side, ey - 1, EYE); at(ex, ey, EYE); at(ex - side, ey + 1, EYE)
                    val fx = if (side < 0) -1 else 14
                    at(fx, 5, BODY); at(fx + (if (side < 0) -1 else 1), 4, BODY); at(fx, 4, DARK); at(fx + (if (side < 0) -1 else 1), 3, DARK)
                }
            }
            if (pose.blush) at(ex + side, 7, BLUSH)
        }
        // the mouth
        when (pose.face) {
            Face.SURPRISED -> { at(6, 8, EYE); at(7, 8, EYE); at(6, 9, EYE); at(7, 9, EYE) }
            Face.CHOMP -> { for (x in 5..8) { at(x, 8, EYE); at(x, 10, EYE) }; at(5, 9, EYE); at(8, 9, EYE); at(6, 9, BLUSH); at(7, 9, BLUSH) }
            Face.BITE -> { at(5, 8, EYE); at(6, 9, EYE); at(7, 8, EYE); at(8, 9, EYE) }
            Face.SING -> if (pose.phase % 2 == 0) { at(6, 8, EYE); at(7, 8, EYE); at(6, 9, BLUSH); at(7, 9, BLUSH); at(6, 10, EYE); at(7, 10, EYE) } else { at(6, 9, EYE); at(7, 9, EYE) }
            Face.DOWN -> { at(6, 9, EYE); at(7, 9, EYE) }
            Face.YAWN -> { for (x in 5..8) at(x, 8, EYE); at(5, 9, EYE); at(6, 9, BLUSH); at(7, 9, BLUSH); at(8, 9, EYE); for (x in 6..7) at(x, 10, EYE) }
            Face.SAD -> { at(5, 9, EYE); at(6, 8, EYE); at(7, 8, EYE); at(8, 9, EYE) }
            Face.COVER -> { at(5, 9, EYE); at(6, 8, EYE); at(7, 9, EYE); at(8, 8, EYE) }
            Face.SLEEP, Face.BLINK -> { at(6, 8, EYE); at(7, 8, EYE) }
            Face.SMUG -> { at(6, 8, EYE); at(7, 8, EYE); at(8, 7, EYE) }
            Face.HAPPY, Face.PAT, Face.STAR -> { at(5, 8, EYE); at(6, 9, EYE); at(7, 9, EYE); at(8, 8, EYE); at(6, 8, BLUSH); at(7, 8, BLUSH) }
            else -> { at(5, 8, EYE); at(6, 9, EYE); at(7, 9, EYE); at(8, 8, EYE) }
        }
        // a fin raised to wave, on the right (and the left, for 做操)
        if (pose.wave) { px(14 + OX, 3 + OY + dy, BODY); px(15 + OX, 2 + OY + dy, BODY); px(15 + OX, 3 + OY + dy, DARK) }
        if (pose.waveLeft) { px(-1 + OX, 3 + OY + dy, BODY); px(-2 + OX, 2 + OY + dy, BODY); px(-2 + OX, 3 + OY + dy, DARK) }
        drawProp(pose, dy, ::at)
        // what goes above
        when (pose.extra) {
            Extra.BANG -> { px(16, 0 + dy, RED); px(16, 1 + dy, RED); px(16, 3 + dy, RED) }
            Extra.HEART -> for ((x, y) in HEART) px(x, y, RED)
            Extra.Z -> {
                val rise = pose.phase % 3
                for ((x, y) in ZED) px(x, y + 2 - rise, ZZZ)
            }
            Extra.BUBBLES -> {
                // two bubbles rising in turn: thinking
                val p = pose.phase % 4
                px(13, 3 - p / 2, BUBBLE)
                if (p >= 1) { px(15, 1 - (p - 1) / 2, BUBBLE); px(16, 1 - (p - 1) / 2, BUBBLE) }
            }
            Extra.NONE -> {}
        }
        // worn on the head, beside the fin
        if (wear != null && pose.extra != Extra.Z) for ((x, y, color) in wear.pixels) px(x + 2, y + 1 + dy, color)
    }

    /**
     * Draws 小K as it is now ([SharkLife]) and returns how many milliseconds until it changes
     * (0: keep animating). [still]: no floating.
     */
    fun drawLive(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Int = 255, still: Boolean = false): Long {
        val (pose, wait) = SharkLife.frame(Motion.now(), java.time.LocalTime.now().hour, still)
        draw(c, cx, cy, size, pose, alpha)
        return if (wait <= 20) 0L else wait
    }

    private const val GREY = 0xFF9AA3AF.toInt()
    private const val GREY_D = 0xFF6B7380.toInt()
    private const val SCREEN = 0xFFBFE3F5.toInt()
    private const val ORANGE = 0xFFF4A340.toInt()
    private const val NOTE = 0xFF6C7BD8.toInt()

    /** The props of the longer acts, drawn in front of it. */
    private fun drawProp(pose: Pose, dy: Int, at: (Int, Int, Int) -> Unit) {
        val ph = pose.phase
        when (pose.prop) {
            Prop.LAPTOP -> {
                // a little laptop, its lid towards us, a light on the lid; the fins tap the keys in turn
                for (x in 3..10) for (y in 8..10) at(x, y, GREY)
                at(6, 9, if (ph % 4 < 2) SCREEN else 0xFFFFFFFF.toInt()); at(7, 9, if (ph % 4 < 2) SCREEN else 0xFFFFFFFF.toInt())
                for (x in 2..11) at(x, 11, GREY_D)
                at(if (ph % 2 == 0) 1 else 2, 10, BODY); at(if (ph % 2 == 0) 12 else 11, 10, if (ph % 2 == 0) BODY else DARK)
            }
            Prop.NOTES -> {
                // notes float up to the right, one after another
                for ((i, base) in intArrayOf(0, 2).withIndex()) {
                    val y = 3 - ((ph + base) % 4) + if (i == 1) 1 else 0
                    val x = 14 + i * 2
                    px(x + OX, y + OY + dy, NOTE); px(x + OX, y + 1 + OY + dy, NOTE); px(x - 1 + OX, y + 2 + OY + dy, NOTE); px(x + OX, y + 2 + OY + dy, NOTE); px(x + 1 + OX, y + OY + dy, NOTE)
                }
            }
            Prop.SHADES -> {
                for (x in 2..6) at(x, 4, EYE); for (x in 7..11) at(x, 4, EYE)
                for (x in 3..5) at(x, 5, EYE); for (x in 8..10) at(x, 5, EYE)
                at(3, 4, SHINE); at(8, 4, SHINE)
            }
            Prop.FISH -> {
                // a little dried fish, going in bite by bite
                val left = 1 + ph
                for (x in left..(left + 2)) if (x < 6) at(x, 8, ORANGE)
                if (left - 1 < 6) { at(left - 1, 7, ORANGE); at(left - 1, 9, ORANGE) }
            }
            Prop.DUMBBELL -> {
                val up = ph % 2 == 0
                val y = if (up) 2 else 4
                for (x in -2..15) if (x !in 0..13 || y < 2) px(x + OX, y + OY + dy, GREY_D)
                for (dyy in -1..1) { px(-3 + OX, y + dyy + OY + dy, GREY); px(16 + OX, y + dyy + OY + dy, GREY) }
            }
            Prop.NONE -> {}
        }
    }

    private val HEART = listOf(14 to 0, 16 to 0, 13 to 1, 14 to 1, 15 to 1, 16 to 1, 17 to 1, 14 to 2, 15 to 2, 16 to 2, 15 to 3)
    private val ZED = listOf(14 to 0, 15 to 0, 16 to 0, 16 to 1, 15 to 2, 14 to 3, 15 to 3, 16 to 3)

    // ---- what it wears: a little something on the days that have a name ----

    class Wear(val name: String, val pixels: List<Triple<Int, Int, Int>>)

    private fun item(name: String, color: Int, vararg cells: Pair<Int, Int>) = Wear(name, cells.map { Triple(it.first, it.second, color) })

    private val SNOW = item("雪花", 0xFFFFFFFF.toInt(), 10 to 3, 9 to 4, 10 to 4, 11 to 4, 10 to 5)
    private val FLOWER = Wear("小花", listOf(Triple(9, 3, 0xFFF6A0B0.toInt()), Triple(11, 3, 0xFFF6A0B0.toInt()), Triple(10, 4, 0xFFFFD54F.toInt()),
        Triple(9, 5, 0xFFF6A0B0.toInt()), Triple(11, 5, 0xFFF6A0B0.toInt())))
    private val LEAF = Wear("落叶", listOf(Triple(10, 3, 0xFFE0903A.toInt()), Triple(9, 4, 0xFFE0903A.toInt()), Triple(10, 4, 0xFFC8702A.toInt()), Triple(11, 4, 0xFFE0903A.toInt())))
    private val LANTERN = Wear("灯笼", listOf(Triple(11, 2, 0xFFFFD54F.toInt()), Triple(10, 3, RED), Triple(11, 3, RED), Triple(12, 3, RED), Triple(10, 4, RED), Triple(11, 4, RED), Triple(12, 4, RED), Triple(11, 5, 0xFFFFD54F.toInt())))
    private val MOON = item("月亮", 0xFFFFE27A.toInt(), 11 to 1, 10 to 2, 10 to 3, 11 to 4)
    private val SUN = item("小太阳", 0xFFFFD54F.toInt(), 10 to 3, 11 to 3, 10 to 4, 11 to 4, 9 to 2, 12 to 2, 9 to 5, 12 to 5)

    private var wearDay: LocalDate? = null
    private var wearCached: Wear? = null

    /** Today's: for festivals and the solar terms of each season; nothing on ordinary days. */
    fun wearToday(today: LocalDate = LocalDate.now()): Wear? {
        if (today == wearDay) return wearCached
        wearDay = today
        wearCached = wearFor(today)
        return wearCached
    }

    fun wearFor(d: LocalDate): Wear? {
        when (com.kk.shurufa.ime.KayLines.festival(d)) {
            "春节", "除夕", "元宵" -> return LANTERN
            "中秋" -> return MOON
        }
        return when (com.kk.shurufa.ime.SolarTerms.on(d)) {
            "小雪", "大雪", "冬至", "小寒", "大寒", "立冬" -> SNOW
            "立春", "雨水", "惊蛰", "春分", "清明", "谷雨" -> FLOWER
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑" -> SUN
            "立秋", "处暑", "白露", "秋分", "寒露", "霜降" -> LEAF
            else -> null
        }
    }

    private object Color8 { fun alpha(c: Int) = (c ushr 24) and 0xFF }
}

/**
 * 小K's life as the keyboard feels it, and the moves it makes. Most of the time it floats (a pixel
 * up, a pixel down, slowly), blinks at irregular times and flicks its fin now and then. Things
 * that happen play a few frames, a second at most:
 *
 * - typing fast: (￣▽￣), keeping up; a run of deletes: fins over its eyes (/ω＼), then a peek
 * - held (摸摸头): squished, (>ω<), a heart; the message sent: a jump, (☆ω☆)
 * - the keyboard after a long while: woken with a start (ﾟдﾟ)!; the line of the day: rising up, a wave
 * - thinking (the AI on its way): looking up, bubbles; nothing typed for a while: (˘ω˘)zZ, and after
 *   five minutes it sinks a pixel lower, asleep; late at night, a yawn now and then
 *
 * And when typing stops for a moment, now and then something of its own: it looks left and right,
 * swims over and nibbles the language's name (which shakes), blows a bubble, turns to look the
 * other way, stretches, or says a small odd thing in place of the name. Never while typing.
 *
 * Nothing here is stored. Between frames nothing is redrawn: [frame] says when the next change is.
 */
object SharkLife {
    private val keys = LongArray(24)
    private var keyAt = 0
    private val dels = LongArray(10)
    private var delAt = 0
    @Volatile var lastKey = 0L; private set
    @Volatile var thinking = false
    private var lastCover = 0L
    private val rnd = java.util.Random()

    private var anim: List<Pair<Shark.Pose, Int>> = emptyList()
    private var animAt = 0L
    private var blinkAt = 0L
    private var flickAt = 0L
    private var yawnAt = 0L

    /** Its pace, from the settings (小K page). */
    data class Tune(
        /** Seconds between the things it does of its own, about. */
        val actEvery: Int = 8,
        /** Of those, the share (%) that are longer acts. */
        val longShare: Int = 35,
        /** How fast it moves when it plays by itself (%; less is slower). */
        val speed: Int = 70,
        /** Seconds between sayings, about. */
        val sayEvery: Int = 40,
        /** Seconds a saying stays. */
        val saySec: Int = 12,
    )
    @Volatile var tune = Tune()

    /** A longer act (唱歌, 敲电脑 …) plays to its end: typing does not cut it short; only a pat does. */
    private var long = false
    private fun play(now: Long, frames: List<Pair<Shark.Pose, Int>>, longAct: Boolean = false, force: Boolean = false, own: Boolean = false) {
        if (long && playing(now) && !force) return
        // what it does of its own goes at its own pace; reactions stay quick
        val k = if (own) 100f / tune.speed.coerceIn(20, 300) else 1f
        anim = if (k == 1f) frames else frames.map { (p, ms) -> p to (ms * k).toInt() }
        animAt = now; long = longAct
    }

    // ---- what it does by itself, when typing pauses ----

    private var nextAct = 0L
    private var nextSay = 0L
    private var lastSay = 0L
    private var sayText: String? = null
    private var sayUntil = 0L
    /** While nibbling: how far (in its pixels) the language's name is pushed, during these frames. */
    private var pushFrames: Set<Int> = emptySet()

    /** The words it says on the space key now, if it is saying something. */
    fun saying(now: Long): String? = sayText?.takeIf { now < sayUntil && now >= sayUntil - sayMs }
    /** How far the saying is shown: it comes in and goes out over a quarter second. */
    fun sayingShown(now: Long): Float {
        val inF = ((now - (sayUntil - sayMs)) / 250f).coerceIn(0f, 1f)
        val outF = ((sayUntil - now) / 250f).coerceIn(0f, 1f)
        return minOf(inF, outF)
    }

    /** How many of its pixels the name beside it is nudged now (a nibble). */
    fun push(now: Long): Int {
        if (pushFrames.isEmpty() || !playing(now)) return 0
        var t = now - animAt
        for ((i, f) in anim.withIndex()) { if (t < f.second) return if (i in pushFrames) 1 else 0; t -= f.second }
        return 0
    }

    /** Its small odd sayings, dealt like cards: none comes again until all have been said. */
    private val WORDS = listOf("咕噜咕噜", "吐个泡泡", "这个字有点咸", "鳍有点酸", "今天水温刚好", "我在巡逻", "数到第三颗泡泡了",
        "嗷呜", "bloop", "буль-буль", "nom nom", "（假装是条鲸鱼）", "刚才游过一个逗号", "嘘，在想事情", "这里风景不错",
        "刚刚有只句号路过", "空格键底下凉凉的", "我把泡泡排好队了", "今天的字很新鲜", "偷偷练了一下倒着游", "鳍上落了个顿号",
        "在等一个好词", "这个拼音长得像海带", "捡到一颗圆滚滚的句号", "水里信号满格", "学会了自由泳", "叼走一个“的”字玩一下",
        "练习眨眼中", "在数你按了几下空格", "刚和一个 emoji 打了招呼", "今日份泡泡已吐完", "肚子里装了十个字母", "尾巴有点痒",
        "偷偷长大了一点点", "这里的光标好亮", "梦见一片海带田", "我是这块键盘的保安", "刚才是谁按了我一下", "练习帅气地转身",
        "想尝尝“鱼”这个字", "把逗号当成了小虾米", "海底今天放晴", "在给泡泡起名字", "今天也很圆", "新学了一个俄语词：рыба",
        "Hello from the deep", "把问号拉直了一下", "在听键盘的声音", "刚刚那几个字打得很稳", "收集了三个句号", "鳍在打拍子",
        "偷看了一眼候选栏", "躲在空格底下乘凉", "数了数，今天有好多字", "把一个错别字藏起来了", "这里比海底暖和")
    private val deck = ArrayList<String>()
    private fun nextWords(): String {
        if (deck.isEmpty()) { deck.addAll(WORDS); deck.shuffle(rnd) }
        return deck.removeAt(deck.size - 1)
    }

    private fun idleAct(now: Long) {
        pushFrames = emptySet()
        if (rnd.nextInt(100) < tune.longShare) return longAct(now)
        when (val r = rnd.nextInt(100)) {
            in 0..29 -> play(now, listOf(P(look = -1) to 420, P() to 140, P(look = 1) to 420, P() to 140, P(F.BLINK) to 110, P() to 150), own = true)
            in 30..49 -> {
                // swims over to the name and has a nibble; the name shakes a little
                pushFrames = setOf(3, 5, 7)
                play(now, listOf(P(look = 1) to 200, P(look = 1, dx = 1) to 120, P(F.CHOMP, dx = 2) to 110, P(F.BITE, dx = 2) to 110,
                    P(F.CHOMP, dx = 2) to 110, P(F.BITE, dx = 2) to 110, P(F.CHOMP, dx = 2) to 110, P(F.BITE, dx = 2) to 140,
                    P(F.HAPPY, dx = 1, blush = true) to 360, P() to 150), own = true)
                if (rnd.nextInt(3) == 0) say(now + 900, "这个字有点咸", sayMs())
            }
            in 50..64 -> play(now, (0 until 4).map { P(F.LOOKUP, extra = Shark.Extra.BUBBLES, phase = it) to 300 } + (P() to 150), own = true)
            in 65..79 -> play(now, listOf(P(flip = true) to 650, P(F.BLINK, flip = true) to 110, P(flip = true, look = 1) to 450, P() to 150), own = true)
            in 80..89 -> play(now, listOf(P(squash = true) to 160, P(F.BLINK, dy = -1) to 260, P(F.HAPPY) to 260, P() to 120), own = true)
            else -> play(now, listOf(P(F.HAPPY) to 300, P(F.BLINK) to 120, P(F.HAPPY, blush = true) to 600, P() to 300), own = true)
        }
    }

    private var sayMs = 1L
    private fun say(at: Long, text: String, ms: Long) { sayText = text; sayUntil = at + ms; sayMs = ms; lastSay = at }
    /** How long a saying stays under the name. */
    private fun sayMs() = tune.saySec.coerceAtLeast(1) * 1000L

    /** The longer acts: a few seconds each, with a word under the name. */
    fun longAct(now: Long, which: Int = rnd.nextInt(5)) {
        pushFrames = emptySet()
        val frames = ArrayList<Pair<Shark.Pose, Int>>()
        val words: String
        when (which) {
            0 -> {  // 唱歌: sways, mouth opening and closing, notes rising
                words = listOf("♪ 啦啦啦～", "♪ 咕噜咕噜歌", "♪ 一只小鲨鱼～").random(rnd.asKotlin())
                for (i in 0 until 14) frames += P(F.SING, phase = i, look = if (i % 4 < 2) -1 else 1, prop = Shark.Prop.NOTES) to 260
            }
            1 -> {  // 敲电脑: a laptop out, eyes down, fins tapping
                words = listOf("在写周报（假的）", "敲敲敲", "查一下“鲨鱼”怎么写").random(rnd.asKotlin())
                frames += P(F.CALM, prop = Shark.Prop.LAPTOP, phase = 0) to 400
                for (i in 0 until 22) frames += P(if (i % 9 == 8) F.BLINK else F.DOWN, prop = Shark.Prop.LAPTOP, phase = i) to 150
                frames += P(F.HAPPY, prop = Shark.Prop.LAPTOP) to 500
            }
            2 -> {  // 晒太阳: shades on, leaning back
                words = listOf("晒一会儿太阳", "（戴上墨镜）", "海面的光刚好").random(rnd.asKotlin())
                frames += P(F.COOL, prop = Shark.Prop.SHADES) to 300
                for (i in 0 until 6) frames += P(F.COOL, dy = 1, prop = Shark.Prop.SHADES, flick = i % 3 == 1) to 600
                frames += P(F.HAPPY) to 300
            }
            3 -> {  // 小鱼干: bite by bite
                words = listOf("嘎嘣脆", "来一根小鱼干", "（偷偷吃零食）").random(rnd.asKotlin())
                for (i in 0 until 5) { frames += P(F.CHOMP, prop = Shark.Prop.FISH, phase = i) to 240; frames += P(F.BITE, prop = Shark.Prop.FISH, phase = i) to 240 }
                frames += P(F.HAPPY, blush = true) to 700
            }
            else -> {  // 做操: fins up left and right, a little lifting
                words = listOf("一二三四", "做操时间到", "鳍要多活动").random(rnd.asKotlin())
                for (i in 0 until 8) frames += P(F.HAPPY, wave = i % 2 == 0, waveLeft = i % 2 == 1, squash = i % 4 == 3) to 330
                for (i in 0 until 4) frames += P(F.PAT, prop = Shark.Prop.DUMBBELL, phase = i, dy = if (i % 2 == 0) -1 else 0) to 380
                frames += P(F.HAPPY) to 300
            }
        }
        play(now, frames + (P() to 200), longAct = true, own = true)
        say(now + 200, words, maxOf(sayMs(), anim.sumOf { it.second }.toLong()))
        nextSay = maxOf(nextSay, sayUntil + spread(tune.sayEvery) / 2)
    }

    /** [sec] seconds, give or take a third, in ms. */
    private fun spread(sec: Int): Long { val ms = sec.coerceAtLeast(1) * 1000L; return ms * 2 / 3 + (rnd.nextDouble() * ms * 2 / 3).toLong() }

    private fun java.util.Random.asKotlin() = kotlin.random.Random(nextLong())
    private fun playing(now: Long) = anim.isNotEmpty() && now - animAt < anim.sumOf { it.second }

    fun key(now: Long) { keys[keyAt++ % keys.size] = now; lastKey = now }

    fun delete(now: Long) {
        dels[delAt++ % dels.size] = now; lastKey = now
        // a run of deletes: it covers its eyes, then peeks
        if (dels.count { it > 0 && now - it < 3500 } >= 6 && now - lastCover > 8000 && !playing(now)) {
            lastCover = now
            play(now, listOf(Shark.Pose(Shark.Face.SURPRISED, extra = Shark.Extra.BANG, dy = -1) to 180, Shark.Pose(Shark.Face.COVER) to 900,
                Shark.Pose(Shark.Face.SAD) to 400, Shark.Pose() to 100))
        }
    }

    fun pat(now: Long) {
        lastKey = now
        pushFrames = emptySet()
        play(now, force = true, frames = listOf(Shark.Pose(Shark.Face.PAT, squash = true, blush = true) to 120, Shark.Pose(Shark.Face.PAT, dy = -2, blush = true, extra = Shark.Extra.HEART) to 200,
            Shark.Pose(Shark.Face.PAT, blush = true, extra = Shark.Extra.HEART) to 520, Shark.Pose(Shark.Face.HAPPY, blush = true) to 700))
    }

    /** A message went out (the field's 发送). */
    fun sent(now: Long) {
        play(now, listOf(Shark.Pose(squash = true) to 90, Shark.Pose(Shark.Face.STAR, dy = -2) to 140, Shark.Pose(Shark.Face.STAR, dy = -1, wave = true) to 220,
            Shark.Pose(Shark.Face.STAR, wave = true) to 260, Shark.Pose(Shark.Face.HAPPY) to 300))
    }

    /** The keyboard came up: after a long while, it wakes with a start. */
    fun woke(now: Long) {
        val away = lastKey == 0L || now - lastKey > 5 * 60_000
        lastKey = now
        if (away) play(now, listOf(Shark.Pose(Shark.Face.SLEEP, dy = 1) to 200, Shark.Pose(Shark.Face.SURPRISED, dy = -1, extra = Shark.Extra.BANG) to 220,
            Shark.Pose(Shark.Face.SURPRISED) to 260, Shark.Pose(Shark.Face.BLINK) to 90, Shark.Pose() to 200))
    }

    /** The line of the day: it rises into view and waves. */
    fun greet(now: Long) {
        play(now, listOf(Shark.Pose(dy = 4) to 70, Shark.Pose(dy = 2) to 70, Shark.Pose(Shark.Face.HAPPY, dy = -1, wave = true) to 200,
            Shark.Pose(Shark.Face.HAPPY, wave = true) to 260, Shark.Pose(Shark.Face.HAPPY) to 220, Shark.Pose(Shark.Face.HAPPY, wave = true) to 260, Shark.Pose() to 200))
    }

    /**
     * The pose for [now] and how many milliseconds until it changes (0: keep animating).
     * [still]: no floating (where a moving thing would distract).
     */
    fun frame(now: Long, hour: Int, still: Boolean = false): Pair<Shark.Pose, Long> {
        if (playing(now)) {
            var t = now - animAt
            for ((p, ms) in anim) { if (t < ms) return p to (ms - t); t -= ms }
        }
        if (anim.isNotEmpty()) { anim = emptyList(); pushFrames = emptySet() }
        if (thinking) return Shark.Pose(Shark.Face.LOOKUP, extra = Shark.Extra.BUBBLES, phase = ((now / 380) % 4).toInt()) to (380 - now % 380)
        val idle = if (lastKey == 0L) 0L else now - lastKey
        // asleep: the z rises once a second; after five minutes it sinks a pixel
        if (idle > 90_000) {
            val deep = idle > 5 * 60_000
            return Shark.Pose(Shark.Face.SLEEP, dy = if (deep) 1 else 0, extra = Shark.Extra.Z, phase = ((now / 1000) % 3).toInt()) to (1000 - now % 1000)
        }
        // now and then, something of its own, typing or not: it does what it likes
        if (nextSay == 0L) nextSay = now + spread(tune.sayEvery) / 2
        if (now >= nextSay && lastKey > 0) {
            nextSay = now + spread(tune.sayEvery)
            if (!(sayText != null && now < sayUntil)) say(now, nextWords(), sayMs())
        }
        if (nextAct == 0L) nextAct = now + spread(tune.actEvery) / 2
        if (now >= nextAct && lastKey > 0) {
            idleAct(now)
            nextAct = now + spread(tune.actEvery) + if (playing(now)) anim.sumOf { it.second } else 0
            if (playing(now)) return frame(now, hour, still)
        }
        // blinking, flicking the fin, yawning at night: planned at irregular times
        if (blinkAt + 140 < now) blinkAt = now + 2800 + rnd.nextInt(4200)
        if (flickAt + 320 < now) flickAt = now + 7000 + rnd.nextInt(9000)
        val night = hour in 0..4 || hour == 23
        if (yawnAt + 900 < now) yawnAt = now + if (night) 25_000L + rnd.nextInt(20_000) else Long.MAX_VALUE / 4
        val fast = keys.count { it > 0 && now - it < 6000 } >= 18
        val float = if (still) 0 else if ((now / 1700) % 2 == 0L) 0 else 1
        val face = when {
            now in yawnAt..(yawnAt + 900) -> Shark.Face.YAWN
            now in blinkAt..(blinkAt + 140) -> Shark.Face.BLINK
            fast -> Shark.Face.SMUG
            else -> Shark.Face.CALM
        }
        val pose = Shark.Pose(face, dy = float, flick = now in flickAt..(flickAt + 320))
        // until the next of: a blink, a flick, a yawn, the next float, the end of fast typing, dozing off
        val events = listOfNotNull(
            if (now < blinkAt) blinkAt - now else blinkAt + 140 - now,
            if (now < flickAt) flickAt - now else flickAt + 320 - now,
            if (now < yawnAt) yawnAt - now else yawnAt + 900 - now,
            if (still) null else 1700 - now % 1700,
            if (fast) 1000L else null,
            if (lastKey > 0) 90_000 - idle else null,
            if (lastKey > 0) nextAct - now else null,
            if (lastKey > 0) nextSay - now else null,
            sayUntil - now,
            sayUntil - sayMs - now,
        ).filter { it > 0 }
        return pose to (events.minOrNull() ?: 1000L).coerceIn(16L, 60_000L)
    }
}
