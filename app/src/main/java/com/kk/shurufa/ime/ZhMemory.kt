package com.kk.shurufa.ime

import android.content.Context
import java.io.File

/**
 * 联想记忆: what this person writes after what. Each Chinese commit that stayed (see
 * [DeferredLearning]) is kept under the last one, two and three characters before it
 * ("今天" → 天气不错, "天" → 天气不错), and an association picked is kept the same way; the
 * associations then put these first, the longest matching context first. Counts halve over
 * three months, so what is no longer written fades; at most a few thousand pairs are kept. Only
 * on this phone.
 */
class ZhMemory(context: Context) {
    private val context: Context = context.applicationContext
    private class Entry(var count: Float, var day: Int)

    /** context → (what followed → how often, and when last). */
    private val map = HashMap<String, HashMap<String, Entry>>()
    @Volatile private var loaded = false
    private var dirty = false
    private val file get() = File(context.filesDir, FILE)

    init { live = this }

    fun load() {
        if (loaded) return
        Background.run {
            val m = HashMap<String, HashMap<String, Entry>>()
            runCatching {
                if (file.exists()) file.forEachLine { l ->
                    val p = l.split('\t')
                    if (p.size == 4) m.getOrPut(p[0]) { HashMap() }[p[1]] = Entry(p[2].toFloatOrNull() ?: 1f, p[3].toIntOrNull() ?: today())
                }
            }
            synchronized(map) { if (!loaded) { m.forEach { (k, v) -> map.getOrPut(k) { HashMap() }.putAll(v) }; loaded = true } }
        }
    }

    /** [next] was written right after [before] (the text before it in the field). */
    fun learn(before: String, next: String) {
        val han = before.takeLastWhile(::isHan)
        val word = next.takeWhile(::isHan)
        if (han.isEmpty() || word.isEmpty() || word.length > MAX_NEXT) return
        val d = today()
        synchronized(map) {
            for (len in 1..minOf(MAX_CTX, han.length)) {
                val e = map.getOrPut(han.takeLast(len)) { HashMap() }.getOrPut(word) { Entry(0f, d) }
                e.count = decayed(e, d) + 1f; e.day = d
            }
            dirty = true
            if (size() > MAX_PAIRS) prune(d)
        }
    }

    /**
     * What this person wrote after the end of [before], best first: a context of three
     * characters before two before one (one character alone has to have been seen twice).
     */
    fun suggest(before: String, max: Int = 8): List<String> {
        val han = before.takeLastWhile(::isHan)
        if (han.isEmpty()) return emptyList()
        val d = today()
        val out = LinkedHashSet<String>()
        synchronized(map) {
            for (len in minOf(MAX_CTX, han.length) downTo 1) {
                val m = map[han.takeLast(len)] ?: continue
                m.entries.map { it.key to decayed(it.value, d) }
                    .filter { (_, s) -> s >= if (len == 1) 1.6f else 0.6f }
                    .sortedByDescending { it.second }
                    .forEach { out += it.first }
                if (out.size >= max) break
            }
        }
        return out.take(max)
    }

    /** Written down when the keyboard goes away, on a background thread. */
    fun save() {
        if (!dirty) return
        val lines = synchronized(map) {
            dirty = false
            map.flatMap { (k, v) -> v.map { (w, e) -> "$k\t$w\t${"%.2f".format(java.util.Locale.ROOT, e.count)}\t${e.day}" } }
        }
        Background.run { runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(lines.joinToString("\n")); tmp.renameTo(file) } }
    }

    fun clear() { synchronized(map) { map.clear(); dirty = false }; file.delete() }

    private fun size() = map.values.sumOf { it.size }

    /** The weakest pairs go, down to four fifths of the cap. */
    private fun prune(d: Int) {
        val all = map.flatMap { (k, v) -> v.map { (w, e) -> Triple(k, w, decayed(e, d)) } }.sortedBy { it.third }
        for ((k, w, _) in all.take(all.size - MAX_PAIRS * 4 / 5)) { map[k]?.remove(w); if (map[k]?.isEmpty() == true) map.remove(k) }
    }

    private fun decayed(e: Entry, d: Int): Float = e.count * Math.pow(0.5, (d - e.day).coerceAtLeast(0) / HALF_LIFE_DAYS).toFloat()

    companion object {
        private const val FILE = "zh_next.tsv"
        private const val MAX_CTX = 3
        private const val MAX_NEXT = 6
        private const val MAX_PAIRS = 6000
        private const val HALF_LIFE_DAYS = 90.0
        private fun today() = (System.currentTimeMillis() / 86_400_000L).toInt()
        private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

        /** The keyboard's own, while it runs (the settings clear it too). */
        @Volatile private var live: ZhMemory? = null

        fun clearAll(context: Context) { live?.clear(); File(context.filesDir, FILE).delete() }
    }
}
