package com.kk.shurufa.ime

import android.content.Context
import java.util.concurrent.Executors

/**
 * 词语联想: after a word is committed, suggests what usually follows it in the dictionary
 * ("中国" → 人民, 银行, 特色 …). The index is a sorted UTF-8 file searched in place.
 */
class Predictor(private val context: Context) {
    @Volatile private var data: ByteArray? = null
    @Volatile private var lines: IntArray? = null
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile private var poetry: Map<String, String>? = null

    /** 古诗文扩展包: line -> the line that follows it. */
    fun loadPoetry() {
        if (poetry != null) return
        exec.execute {
            if (poetry != null) return@execute
            val m = HashMap<String, String>(40000)
            context.assets.open("data/poetry_next.txt").bufferedReader().forEachLine { l ->
                val t = l.indexOf('\t')
                if (t > 0) m[l.substring(0, t)] = l.substring(t + 1)
            }
            poetry = m
        }
    }

    fun dropPoetry() { poetry = null }

    /** The next line of a poem when [before] ends with one of its lines (punctuation allowed after it). */
    fun nextLine(before: String): String? {
        val p = poetry ?: return null
        val t = before.trimEnd { !Character.isLetterOrDigit(it) && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN }
        for (len in minOf(14, t.length) downTo 4) p[t.takeLast(len)]?.let { return it }
        return null
    }

    fun load() {
        if (data != null) return
        exec.execute {
            if (data != null) return@execute
            val bytes = context.assets.open("data/predict_zh.txt").use { it.readBytes() }
            var n = 0
            for (b in bytes) if (b == '\n'.code.toByte()) n++
            val idx = IntArray(n)
            var k = 0
            idx[k++] = 0
            for (i in 0 until bytes.size - 1) if (bytes[i] == '\n'.code.toByte() && k < n) idx[k++] = i + 1
            lines = idx
            data = bytes
        }
    }

    private fun keyAt(i: Int, bytes: ByteArray, idx: IntArray): Pair<Int, Int> {
        val start = idx[i]
        var tab = start
        while (bytes[tab] != '\t'.code.toByte()) tab++
        return start to tab
    }

    private fun compare(bytes: ByteArray, from: Int, to: Int, key: ByteArray): Int {
        val len = to - from
        val m = minOf(len, key.size)
        for (j in 0 until m) {
            val a = bytes[from + j].toInt() and 0xff
            val b = key[j].toInt() and 0xff
            if (a != b) return a - b
        }
        return len - key.size
    }

    /** Continuations for [key], best first; empty while the index is still loading. */
    fun lookup(key: String): List<String> {
        val bytes = data ?: return emptyList()
        val idx = lines ?: return emptyList()
        val kb = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = idx.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (s, t) = keyAt(mid, bytes, idx)
            val c = compare(bytes, s, t, kb)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    var e = t + 1
                    while (e < bytes.size && bytes[e] != '\n'.code.toByte()) e++
                    return String(bytes, t + 1, e - t - 1, Charsets.UTF_8).split(' ').filter { it.isNotEmpty() }
                }
            }
        }
        return emptyList()
    }

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
        return out.take(16)
    }
}
