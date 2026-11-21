package com.kk.shurufa.ime

import android.content.Context
import java.text.Normalizer

/**
 * 拼音模式: turns committed Chinese into tone-marked pinyin. The syllables the user actually
 * typed decide which reading of a polyphonic character is meant (行 xíng / háng).
 */
class PinyinTones(private val context: Context) {
    @Volatile private var table: Map<Char, List<String>>? = null

    /** Reads the table now (call it off the main thread, so the first conversion does not wait). */
    fun preload() { table() }

    private fun table(): Map<Char, List<String>> = table ?: synchronized(this) {
        table ?: HashMap<Char, List<String>>(48000).also { m ->
            context.assets.open("data/pinyin_tones.txt").bufferedReader().forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab == 1) m[line[0]] = line.substring(2).split(',')
            }
            table = m
        }
    }

    /**
     * Removes tone marks and writes ü as v, to compare with what was typed. ü is found after
     * decomposition, so ǖ ǘ ǚ ǜ become v too.
     */
    private fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace("u\u0308", "v").replace("U\u0308", "V").replace(MARKS, "").lowercase()

    private companion object { val MARKS = Regex("[\\u0300-\\u036f]") }

    /** [syllables] are the typed syllables in order (may be shorter than the text). */
    fun convert(text: String, syllables: List<String>): String {
        val t = table()
        val out = mutableListOf<String>()
        var si = 0
        for (ch in text) {
            val readings = t[ch]
            if (readings == null) { out += ch.toString(); continue }
            val typed = syllables.getOrNull(si)?.lowercase()?.replace("ü", "v")
            si++
            val pick = typed?.let { ty -> readings.firstOrNull { plain(it) == ty } ?: readings.firstOrNull { plain(it).startsWith(ty) } }
            out += pick ?: readings[0]
        }
        // join syllables with spaces, keep punctuation attached
        val sb = StringBuilder()
        out.forEachIndexed { i, s ->
            if (i > 0 && s.firstOrNull()?.isLetter() == true && out[i - 1].lastOrNull()?.isLetter() == true) sb.append(' ')
            sb.append(s)
        }
        return sb.toString()
    }
}
