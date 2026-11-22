package com.kk.shurufa.ime

import android.content.Context

/**
 * 词语联想: after a word is committed, suggests what usually follows it in the dictionary
 * ("中国" → 人民, 银行, 特色 …), and with 古诗文 on, the next line of a poem. Both tables are
 * sorted UTF-8 files searched in place: nothing is parsed into objects, so they cost little
 * more memory than their bytes.
 */
class Predictor(private val context: Context) {
    @Volatile private var words: TsvIndex? = null
    @Volatile private var poetry: TsvIndex? = null

    fun load() {
        if (words != null) return
        Background.run { if (words == null) words = TsvIndex(context.assets.open("data/predict_zh.txt").use { it.readBytes() }) }
    }

    /** 古诗文扩展包: line → the line that follows it. */
    fun loadPoetry() {
        if (poetry != null) return
        Background.run { if (poetry == null) poetry = TsvIndex(context.assets.open("data/poetry_next.txt").use { it.readBytes() }) }
    }

    fun dropPoetry() { poetry = null }

    /** The next line of a poem when [before] ends with one of its lines (punctuation allowed after it). */
    fun nextLine(before: String): String? {
        val p = poetry ?: return null
        val t = before.trimEnd { !Character.isLetterOrDigit(it) && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN }
        for (len in minOf(14, t.length) downTo 4) p[t.takeLast(len)]?.let { return it }
        return null
    }

    /** Continuations for [key], best first; empty while the index is still loading. */
    fun lookup(key: String): List<String> = words?.get(key)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

    /** Suggestions for the text just before the cursor: tries the last 4, 3, 2 and 1 characters. */
    fun suggest(before: String): List<String> {
        val out = LinkedHashSet<String>()
        nextLine(before)?.let { out += it }
        val han = before.takeLastWhile { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        if (han.isEmpty()) return out.toList()
        for (len in minOf(4, han.length) downTo 1) {
            lookup(han.takeLast(len)).forEach { out += it }
            if (out.size >= 12) break
        }
        return out.filter { !Muted.has(context, it) }.take(16)
    }
}

/**
 * A file of `key<TAB>value` lines sorted by the UTF-8 bytes of the key, searched in place with a
 * binary search over line starts.
 */
class TsvIndex(private val bytes: ByteArray) {
    private val starts: IntArray

    init {
        var n = 0
        for (i in bytes.indices) if (bytes[i] == NL && i + 1 < bytes.size) n++
        val idx = IntArray(if (bytes.isEmpty()) 0 else n + 1)
        var k = 0
        if (bytes.isNotEmpty()) idx[k++] = 0
        for (i in 0 until bytes.size - 1) if (bytes[i] == NL) idx[k++] = i + 1
        starts = idx
    }

    val size get() = starts.size

    /** The value for [key], or null. */
    operator fun get(key: String): String? {
        val kb = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = starts[mid]
            var tab = s
            while (tab < bytes.size && bytes[tab] != TAB && bytes[tab] != NL) tab++
            val c = compare(s, tab, kb)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    if (tab >= bytes.size || bytes[tab] != TAB) return ""
                    var e = tab + 1
                    while (e < bytes.size && bytes[e] != NL) e++
                    return String(bytes, tab + 1, e - tab - 1, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    /**
     * Calls [f] with (key, value) for each line whose key starts with [prefix], in key order,
     * at most [limit] lines.
     */
    fun prefix(prefix: String, limit: Int, f: (String, String) -> Unit) {
        val pb = prefix.toByteArray(Charsets.UTF_8)
        // first line whose key is not below the prefix
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val s = starts[mid]
            var tab = s
            while (tab < bytes.size && bytes[tab] != TAB && bytes[tab] != NL) tab++
            if (compare(s, tab, pb) < 0) lo = mid + 1 else hi = mid
        }
        var i = lo
        var n = 0
        while (i < starts.size && n < limit) {
            val s = starts[i]
            var tab = s
            while (tab < bytes.size && bytes[tab] != TAB && bytes[tab] != NL) tab++
            if (tab - s < pb.size) break
            for (j in pb.indices) if (bytes[s + j] != pb[j]) return
            var e = tab
            while (e < bytes.size && bytes[e] != NL) e++
            val v = if (tab < e) String(bytes, tab + 1, e - tab - 1, Charsets.UTF_8) else ""
            f(String(bytes, s, tab - s, Charsets.UTF_8), v)
            i++; n++
        }
    }

    private fun compare(from: Int, to: Int, key: ByteArray): Int {
        val len = to - from
        val m = minOf(len, key.size)
        for (j in 0 until m) {
            val a = bytes[from + j].toInt() and 0xff
            val b = key[j].toInt() and 0xff
            if (a != b) return a - b
        }
        return len - key.size
    }

    private companion object {
        const val NL = '\n'.code.toByte()
        const val TAB = '\t'.code.toByte()
    }
}
