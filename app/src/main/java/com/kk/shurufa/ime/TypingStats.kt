package com.kk.shurufa.ime

import android.content.Context
import android.icu.text.BreakIterator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * 打字数据: how much was written, how (typed, spoken, pasted, by AI), when in the day, and the
 * words used most, for the statistics page and its word cloud. Everything stays in one file in
 * the app's own storage; nothing is sent anywhere. Fields that ask not to be learned from
 * (passwords, addresses, incognito) are never recorded.
 *
 * Text is only counted, never kept: words are split out (ICU's dictionary segmenter, which knows
 * Chinese) and tallied, the rest is thrown away. The tallies keep the most used words only.
 */
class TypingStats private constructor(private val file: File) {
    enum class Source { TYPED, VOICE, PASTE, AI }

    /** One day: characters by source. */
    class Day(val chars: IntArray = IntArray(Source.entries.size)) { val total get() = chars.sum() }

    private val lock = Any()
    private val days = sortedMapOf<String, Day>()
    private val hours = IntArray(24)
    private val words = HashMap<String, Int>()
    private val emoji = HashMap<String, Int>()
    private var since = ""
    private var dirty = false

    init { runCatching { load() } }

    /** Counts [text] written into a field by [source]. Cheap; the splitting happens in the background. */
    fun record(text: String, source: Source) {
        val n = text.codePoints().filter { !Character.isWhitespace(it) }.count().toInt()
        if (n == 0) return
        val today = LocalDate.now().toString()
        val hour = LocalTime.now().hour
        synchronized(lock) {
            if (since.isEmpty()) since = today
            days.getOrPut(today) { Day() }.chars[source.ordinal] += n
            hours[hour] += n
            dirty = true
        }
        if (source == Source.TYPED || source == Source.VOICE) Background.run { tally(text) }
        scheduleSave()
    }

    private val splitter by lazy { BreakIterator.getWordInstance(Locale.CHINESE) }

    /** Splits [text] into words and counts the ones worth a place in the cloud. */
    private fun tally(text: String) {
        val found = ArrayList<String>()
        val it = splitter
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val w = text.substring(start, end)
            when {
                isEmoji(w) -> synchronized(lock) { emoji[w] = (emoji[w] ?: 0) + 1 }
                worthy(w) -> found += w
            }
            start = end; end = it.next()
        }
        if (found.isEmpty()) return
        synchronized(lock) {
            for (w in found) words[w] = (words[w] ?: 0) + 1
            if (words.size > MAX_WORDS * 2) prune()
        }
    }

    /** Words for the cloud: two or more Chinese characters, or a Latin or Cyrillic word of three letters or more. */
    private fun worthy(w: String): Boolean {
        if (w.length >= 2 && w.length <= 8 && w.all { isCjk(it) }) return w !in STOP
        return w.length in 3..20 && w.all { it.isLetter() } && !w.any { isCjk(it) }
    }

    private fun isEmoji(w: String): Boolean {
        val cp = w.codePointAt(0)
        return cp >= 0x1F000 || cp in 0x2600..0x27BF
    }

    private fun prune() {
        val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS)
        words.clear(); keep.forEach { words[it.key] = it.value }
    }

    // ---- reading ----

    class Snapshot(
        val days: Map<String, Day>,
        val hours: IntArray,
        val words: List<Pair<String, Int>>,
        val emoji: List<Pair<String, Int>>,
        val since: String,
    ) {
        val total get() = days.values.sumOf { it.total }
        fun bySource(s: Source) = days.values.sumOf { it.chars[s.ordinal] }
        /** Days in a row, ending today or yesterday, with something written. */
        val streak: Int get() {
            var d = LocalDate.now()
            if ((days[d.toString()]?.total ?: 0) == 0) d = d.minusDays(1)
            var n = 0
            while ((days[d.toString()]?.total ?: 0) > 0) { n++; d = d.minusDays(1) }
            return n
        }
        /** The last [n] days, oldest first, with their totals (zero for days without any). */
        fun last(n: Int): List<Pair<LocalDate, Int>> {
            val today = LocalDate.now()
            return (n - 1 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (days[d.toString()]?.total ?: 0) } }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            days.mapValues { Day(it.value.chars.copyOf()) }, hours.copyOf(),
            words.entries.sortedByDescending { it.value }.take(120).map { it.key to it.value },
            emoji.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value },
            since,
        )
    }

    fun clear() {
        synchronized(lock) { days.clear(); hours.fill(0); words.clear(); emoji.clear(); since = ""; dirty = false }
        Background.run { file.delete() }
    }

    // ---- storage ----

    private var saveQueued = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Saves a while after the first change, waiting on the main thread's clock, not on the background thread others share. */
    private fun scheduleSave() {
        synchronized(lock) { if (saveQueued) return; saveQueued = true }
        main.postDelayed({ Background.run { save() } }, SAVE_DELAY_MS)
    }

    /** Writes the tallies if anything changed. */
    fun save() {
        val json = synchronized(lock) {
            saveQueued = false
            if (!dirty) return
            dirty = false
            JSONObject().apply {
                put("since", since)
                put("days", JSONObject().apply { days.forEach { (k, v) -> put(k, JSONArray(v.chars.toList())) } })
                put("hours", JSONArray(hours.toList()))
                put("words", JSONObject(words as Map<*, *>))
                put("emoji", JSONObject(emoji as Map<*, *>))
            }.toString()
        }
        runCatching { file.parentFile?.mkdirs(); val tmp = File(file.path + ".tmp"); tmp.writeText(json); tmp.renameTo(file) }
    }

    private fun load() {
        if (!file.isFile) return
        val o = JSONObject(file.readText())
        since = o.optString("since")
        o.optJSONObject("days")?.let { d ->
            for (k in d.keys()) {
                val a = d.getJSONArray(k)
                days[k] = Day(IntArray(Source.entries.size) { i -> if (i < a.length()) a.getInt(i) else 0 })
            }
        }
        o.optJSONArray("hours")?.let { a -> for (i in 0 until minOf(24, a.length())) hours[i] = a.getInt(i) }
        o.optJSONObject("words")?.let { w -> for (k in w.keys()) words[k] = w.getInt(k) }
        o.optJSONObject("emoji")?.let { w -> for (k in w.keys()) emoji[k] = w.getInt(k) }
    }

    companion object {
        private const val MAX_WORDS = 1500
        private const val SAVE_DELAY_MS = 20_000L
        /** Words too common to say anything about the person typing them. */
        private val STOP = setOf("我们", "你们", "他们", "她们", "这个", "那个", "什么", "一个", "没有", "就是", "可以", "因为", "所以", "但是", "然后", "如果", "这样", "那样", "还是", "已经", "自己", "现在", "时候", "怎么", "知道", "不是", "这么", "那么", "一下", "一些", "这些", "那些", "的话")

        @Volatile private var instance: TypingStats? = null

        /** Forgets the instance, so the next [get] reads the file again (tests). */
        @androidx.annotation.VisibleForTesting
        fun reload() { instance = null }

        /** The one instance of the process, shared by the keyboard and the settings. */
        fun get(ctx: Context): TypingStats = instance ?: synchronized(this) {
            instance ?: TypingStats(File(ctx.applicationContext.filesDir, "typing_stats.json")).also { instance = it }
        }
    }
}
