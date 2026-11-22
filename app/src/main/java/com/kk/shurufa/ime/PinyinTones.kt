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

    companion object {
        private val MARKS = Regex("[\\u0300-\\u036f]")
        private val TONED = mapOf(
            'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'v' to "ǖǘǚǜ",
        )

        /**
         * A typed syllable with tone [tone] (1–4) marked where pinyin puts it: on a or e, on the o
         * of ou, otherwise on the last vowel (nǐ, hǎo, guī, liù, nǚ). v is written ü. Without a
         * vowel (an initial typed alone) the syllable stays as it is.
         */
        fun mark(syllable: String, tone: Int): String {
            val s = syllable.lowercase()
            val at = when {
                'a' in s -> s.indexOf('a')
                'e' in s -> s.indexOf('e')
                "ou" in s -> s.indexOf("ou")
                else -> s.indexOfLast { it in "iouv" }
            }
            val out = StringBuilder(s)
            if (at >= 0 && tone in 1..4) out.setCharAt(at, TONED.getValue(s[at])[tone - 1])
            return out.toString().replace('v', 'ü')
        }
    }

    /**
     * 声调筛选: the tones (1–4, 5 for the neutral tone) [ch] has when read as [syllable] (what was
     * typed for it); all its tones when no reading matches, empty when it is not in the table.
     */
    fun tonesOf(ch: Char, syllable: String?): Set<Int> {
        val readings = table()[ch] ?: return emptySet()
        val typed = syllable?.lowercase()?.replace("ü", "v")
        val match = typed?.let { ty -> readings.filter { plain(it) == ty }.ifEmpty { readings.filter { plain(it).startsWith(ty) } } }
        return (match?.ifEmpty { null } ?: readings).map { toneOf(it) }.toSet()
    }

    /** 1 ā, 2 á, 3 ǎ, 4 à, 5 none. */
    private fun toneOf(reading: String): Int {
        val d = Normalizer.normalize(reading, Normalizer.Form.NFD)
        return when {
            d.contains('\u0304') -> 1
            d.contains('\u0301') -> 2
            d.contains('\u030C') -> 3
            d.contains('\u0300') -> 4
            else -> 5
        }
    }

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
