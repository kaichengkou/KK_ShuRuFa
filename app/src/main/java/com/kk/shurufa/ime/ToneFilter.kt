package com.kk.shurufa.ime

import com.kk.shurufa.rime.RimeBridge

/**
 * 声调筛选 in the opened candidates, syllable by syllable: the tone kept for each syllable typed
 * (1–4, 0 for any), for one pinyin only. With tones picked, only the candidates whose characters
 * have them are shown, each for its own syllable (行 is xíng for "xing", háng for "hang"); a
 * candidate shorter than the syllables is checked as far as it goes. Positions in what is shown
 * map back to Rime's own candidates with [rimeIndex].
 */
class ToneFilter(private val tones: PinyinTones) {
    /** The tone kept for each syllable, for the pinyin in [forInput]. */
    private var picked = IntArray(0)
    private var forInput = ""
    /** The syllable the tone row is for. */
    var syllable = 0
    /** While filtering: position on the bar or in the grid → Rime's own candidate index. */
    private var shownToRime: IntArray? = null

    val filtering get() = picked.any { it != 0 }

    fun rimeIndex(i: Int) = shownToRime?.getOrNull(i) ?: i

    /** Every tone let go (the grid is closed). */
    fun reset() { picked = IntArray(0) }

    /** The syllables still to be chosen for (what Rime shows after any part already picked). */
    fun syllables(preedit: String = RimeBridge.preedit()): List<String> =
        preedit.split(' ', '\'').filter { s -> s.isNotEmpty() && s.all { it in 'a'..'z' } }

    /** Starts over when the pinyin is another one (or has another number of syllables). */
    private fun follow(input: String, count: Int, resetSyllable: Boolean) {
        if (input != forInput || picked.size != count) {
            picked = IntArray(count); forInput = input
            if (resetSyllable) syllable = 0
        }
    }

    /** [tone] kept for the chosen syllable; then on to the next one without a tone, so 你好 is two taps. */
    fun pick(tone: Int): Boolean {
        val syl = syllables()
        if (syl.isEmpty()) return false
        follow(RimeBridge.input(), syl.size, resetSyllable = false)
        val at = syllable.coerceIn(0, syl.size - 1)
        picked[at] = tone
        if (tone != 0) (1 until syl.size).map { (at + it) % syl.size }.firstOrNull { picked[it] == 0 }?.let { syllable = it }
        return true
    }

    /**
     * The tone rows: the syllables (each with the tone picked for it, nǐ·hao; null with only one),
     * and the four tones of the chosen one written on it (nī ní nǐ nì), so it is plain which
     * character is being narrowed. Null when there is nothing to narrow.
     */
    class Rows(val syllables: List<Pair<String, String>>?, val syllable: String, val tones: List<Pair<String, String>>, val tone: String)

    fun rows(): Rows? {
        val syl = syllables()
        follow(RimeBridge.input(), syl.size, resetSyllable = true)
        syllable = syllable.coerceIn(0, (syl.size - 1).coerceAtLeast(0))
        if (syl.isEmpty()) return null
        val s = syl[syllable]
        val vowel = s.any { it in "aeiouv" }
        return Rows(
            if (syl.size > 1) syl.mapIndexed { i, x -> i.toString() to PinyinTones.mark(x, picked[i]) } else null, syllable.toString(),
            listOf("0" to "全部") + (1..4).map { t -> t.toString() to (if (vowel) PinyinTones.mark(s, t) else "ˉˊˇˋ"[t - 1].toString()) },
            picked[syllable].toString(),
        )
    }

    /** Up to [n] candidates with their comments; with tones picked, read from the first [pool]. */
    fun candidates(n: Int, pool: Int): Pair<List<String>, List<String>> {
        if (filtering && RimeBridge.input() != forInput) picked = IntArray(0)
        if (!filtering) {
            shownToRime = null
            return RimeBridge.menu(n).take(n)
        }
        val menu = RimeBridge.menu(pool)
        val all = menu.texts
        val syl = syllables()
        val keep = all.indices.filter { i ->
            val c = all[i]
            picked.indices.all { k -> picked[k] == 0 || (k >= c.length && k > 0) || c.getOrNull(k)?.let { ch -> picked[k] in tones.tonesOf(ch, syl.getOrNull(k)) } == true }
        }
        shownToRime = keep.toIntArray()
        val shown = keep.take(n)
        return shown.map { all[it] } to shown.map { menu.comments.getOrElse(it) { "" } }
    }
}
