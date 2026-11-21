package com.kk.shurufa.ime

import android.content.Context
import com.kk.shurufa.Prefs

/**
 * What Chinese typing needs beyond Rime itself: which syllables produced the characters chosen so
 * far (拼音模式 and 删除后联想 read them), where syllables start in the typed letters, the caret
 * in the preedit, and the small notes after candidates — 纠错批注 (p→o, no⇄on), rime-ice's
 * 错音提示, 生僻字注音. The data it needs is read in the background before the first key.
 */
class ZhComposer(context: Context, private val prefs: Prefs) {
    val tones = PinyinTones(context)
    private val assets = context.assets
    @Volatile private var common: Set<Char>? = null

    /** Reads the tone table and the common characters off the main thread. */
    fun preload() = Background.run {
        tones.preload()
        if (common == null) common = runCatching { assets.open("data/common_chars.txt").bufferedReader().readText().toSet() }.getOrDefault(emptySet())
    }

    // ---- 拼音模式: the syllables of the chosen part of the composition, kept as it is confirmed ----
    private val confirmed = mutableListOf<String>()
    private var hanPrefix = 0
    private var pending: List<String> = emptyList()

    fun reset() { confirmed.clear(); hanPrefix = 0; pending = emptyList() }

    /** The syllables behind the committed text, in order. */
    fun syllables(): List<String> = confirmed + pending

    /** Follows the preedit ("今天 tian qi") to remember which syllables produced the chosen characters. */
    fun track(preedit: String) {
        val tokens = preedit.split(' ', '\'').filter { it.isNotEmpty() }
        val han = tokens.takeWhile { t -> t.all { isCjk(it) } }.sumOf { it.length }
        val syl = tokens.dropWhile { t -> t.all { isCjk(it) } }
        if (han > hanPrefix) confirmed += pending.take(han - hanPrefix)
        hanPrefix = han
        pending = syl
    }

    /** Where syllables start in the typed letters, read from the pinyin part of the preedit. */
    fun gaps(input: String, preedit: String): Set<Int> {
        val tokens = preedit.split(' ', '\'').filter { it.isNotEmpty() }
        val syl = tokens.dropWhile { t -> t.any { isCjk(it) } }.map { it.filter { c -> !isCjk(c) } }
        val gaps = mutableSetOf<Int>()
        var p = input.length - syl.sumOf { it.length } - input.count { it == '\'' }
        if (p < 0) return emptySet()
        for (t in syl) {
            if (p > 0) gaps += p
            p += t.length
            if (p < input.length && input[p] == '\'') p++
        }
        return gaps
    }

    /** Caret position in the preedit when it is not at the end, else -1 ([cursorBytes] is Rime's UTF-8 offset). */
    fun caretInPreedit(preedit: String, cursorBytes: Int): Int {
        val utf8 = preedit.toByteArray(Charsets.UTF_8)
        if (cursorBytes <= 0 || cursorBytes >= utf8.size) return -1
        return String(utf8, 0, cursorBytes, Charsets.UTF_8).length
    }

    /** Maps a tap at [index] of the preedit to a position in the typed [input], or -1 on a chosen character. */
    fun inputPosition(preedit: String, input: String, index: Int): Int {
        var j = 0
        for (k in 0 until minOf(index, preedit.length)) {
            val ch = preedit[k]
            if (isCjk(ch)) return -1
            if (j < input.length && (ch == input[j] || ((ch == 'u' || ch == 'ü') && input[j] == 'v'))) j++
        }
        return j
    }

    /** The notes shown above candidates, aligned with them. */
    fun annotations(cands: List<String>, comments: List<String>): List<String> {
        val rare = prefs.rareAnnotate
        val common = if (rare) common else null
        return cands.mapIndexed { i, w ->
            val c = comments.getOrNull(i).orEmpty()
            val m = TYPO.find(c)
            when {
                m != null -> if (prefs.typoMark) typoNote(m.groupValues[1], m.groupValues[2]) else ""
                c.isNotEmpty() && !c.startsWith("［") -> c.take(14)
                // 生僻字注音 waits for its data: until then no note rather than a stall
                common != null && common.isNotEmpty() && w.length in 1..4 && w.all { isCjk(it) } && w.any { it !in common } -> tones.convert(w, emptyList())
                else -> ""
            }
        }
    }

    /** The proofreading mark of a correction: one key swapped (p→o) or two keys in the wrong order (no⇄on). */
    private fun typoNote(typed: String, want: String): String {
        val i = typed.indices.firstOrNull { typed[it] != want.getOrNull(it) } ?: return ""
        return if (i + 1 < typed.length && typed[i] == want[i + 1] && typed[i + 1] == want[i]) "${typed[i]}${typed[i + 1]}⇄${want[i]}${want[i + 1]}"
        else "${typed[i]}→${want[i]}"
    }

    private companion object {
        val TYPO = Regex("^〔kk:([a-z']+)>([a-z']+)〕$")
    }
}
