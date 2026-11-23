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
        CHOMP("(>▽<)嗷"), BITE("(>ω<)")
    }
    enum class Extra { NONE, BANG, HEART, Z, BUBBLES }

    /** One frame: a face and how the body is held. */
    data class Pose(
        val face: Face = Face.CALM, val dy: Int = 0, val squash: Boolean = false, val wave: Boolean = false,
        val blush: Boolean = false, val extra: Extra = Extra.NONE, val phase: Int = 0, val flick: Boolean = false,
        /** Where the eyes look (−1 left, 1 right), how far it has moved (whole pixels), facing the other way. */
        val look: Int = 0, val dx: Int = 0, val flip: Boolean = false,
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
                Face.CALM -> { at(ex, ey, EYE); at(ex, ey - 1, EYE) }
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
            Face.YAWN -> { for (x in 5..8) at(x, 8, EYE); at(5, 9, EYE); at(6, 9, BLUSH); at(7, 9, BLUSH); at(8, 9, EYE); for (x in 6..7) at(x, 10, EYE) }
            Face.SAD -> { at(5, 9, EYE); at(6, 8, EYE); at(7, 8, EYE); at(8, 9, EYE) }
            Face.COVER -> { at(5, 9, EYE); at(6, 8, EYE); at(7, 9, EYE); at(8, 8, EYE) }
            Face.SLEEP, Face.BLINK -> { at(6, 8, EYE); at(7, 8, EYE) }
            Face.SMUG -> { at(6, 8, EYE); at(7, 8, EYE); at(8, 7, EYE) }
            Face.HAPPY, Face.PAT, Face.STAR -> { at(5, 8, EYE); at(6, 9, EYE); at(7, 9, EYE); at(8, 8, EYE); at(6, 8, BLUSH); at(7, 8, BLUSH) }
            else -> { at(5, 8, EYE); at(6, 9, EYE); at(7, 9, EYE); at(8, 8, EYE) }
        }
        // a fin raised to wave, on the right
        if (pose.wave) { px(14 + OX, 3 + OY + dy, BODY); px(15 + OX, 2 + OY + dy, BODY); px(15 + OX, 3 + OY + dy, DARK) }
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

    private fun play(now: Long, frames: List<Pair<Shark.Pose, Int>>) { anim = frames; animAt = now }

    // ---- what it does by itself, when typing pauses ----

    private var nextAct = 0L
    private var lastSay = 0L
    private var sayText: String? = null
    private var sayUntil = 0L
    /** While nibbling: how far (in its pixels) the language's name is pushed, during these frames. */
    private var pushFrames: Set<Int> = emptySet()

    /** The words it says on the space key now, if it is saying something. */
    fun saying(now: Long): String? = sayText?.takeIf { now < sayUntil }

    /** How many of its pixels the name beside it is nudged now (a nibble). */
    fun push(now: Long): Int {
        if (pushFrames.isEmpty() || !playing(now)) return 0
        var t = now - animAt
        for ((i, f) in anim.withIndex()) { if (t < f.second) return if (i in pushFrames) 1 else 0; t -= f.second }
        return 0
    }

    private val WORDS = listOf("咕噜咕噜", "吐个泡泡", "这个字有点咸", "鳍有点酸", "今天水温刚好", "我在巡逻", "数到第三颗泡泡了",
        "嗷呜", "bloop", "буль-буль", "nom nom", "（假装是条鲸鱼）", "刚才游过一个逗号", "嘘，在想事情", "这里风景不错")

    private fun idleAct(now: Long) {
        pushFrames = emptySet()
        when (val r = rnd.nextInt(100)) {
            in 0..29 -> play(now, listOf(P(look = -1) to 420, P() to 140, P(look = 1) to 420, P() to 140, P(F.BLINK) to 110, P() to 150))
            in 30..49 -> {
                // swims over to the name and has a nibble; the name shakes a little
                pushFrames = setOf(3, 5, 7)
                play(now, listOf(P(look = 1) to 200, P(look = 1, dx = 1) to 120, P(F.CHOMP, dx = 2) to 110, P(F.BITE, dx = 2) to 110,
                    P(F.CHOMP, dx = 2) to 110, P(F.BITE, dx = 2) to 110, P(F.CHOMP, dx = 2) to 110, P(F.BITE, dx = 2) to 140,
                    P(F.HAPPY, dx = 1, blush = true) to 360, P() to 150))
                if (rnd.nextInt(3) == 0 && now - lastSay > 60_000) say(now + 1300, "这个字有点咸", 1600)
            }
            in 50..64 -> play(now, (0 until 4).map { P(F.LOOKUP, extra = Shark.Extra.BUBBLES, phase = it) to 300 } + (P() to 150))
            in 65..79 -> play(now, listOf(P(flip = true) to 650, P(F.BLINK, flip = true) to 110, P(flip = true, look = 1) to 450, P() to 150))
            in 80..89 -> play(now, listOf(P(squash = true) to 160, P(F.BLINK, dy = -1) to 260, P(F.HAPPY) to 260, P() to 120))
            else -> {
                if (now - lastSay > 90_000) say(now, WORDS[rnd.nextInt(WORDS.size)], 1800)
                play(now, listOf(P(F.HAPPY) to 300, P() to 1500))
            }
        }
    }

    private fun say(at: Long, text: String, ms: Long) { sayText = text; sayUntil = at + ms; lastSay = at }
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
        play(now, listOf(Shark.Pose(Shark.Face.PAT, squash = true, blush = true) to 120, Shark.Pose(Shark.Face.PAT, dy = -2, blush = true, extra = Shark.Extra.HEART) to 200,
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
        // a pause in typing: now and then, something of its own
        if (idle in 3000..85_000 && now >= nextAct && nextAct > 0) {
            nextAct = now + 7000 + rnd.nextInt(9000)
            idleAct(now)
            if (playing(now)) return frame(now, hour, still)
        }
        if (nextAct == 0L || idle < 3000) nextAct = maxOf(nextAct, now + 4000 + rnd.nextInt(6000))
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
            if (idle < 85_000) nextAct - now else null,
            sayUntil - now,
        ).filter { it > 0 }
        return pose to (events.minOrNull() ?: 1000L).coerceIn(16L, 60_000L)
    }
}
