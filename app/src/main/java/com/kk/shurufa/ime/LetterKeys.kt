package com.kk.shurufa.ime

import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyboardLayout
import com.kk.shurufa.keyboard.Output

/** What the letter keys carry: their rows with the switches that change them, and what a long press offers. */
internal class LetterKeys(private val prefs: Prefs) {
    private var rowsKey = ""
    private var rowsCache: List<List<Key>> = emptyList()

    /** The letter rows of [layout] with the switches applied; the same list while nothing changed. */
    fun rows(lang: String, layout: KeyboardLayout): List<List<Key>> {
        val key = "$lang|${prefs.stressMode}|${prefs.topRowScripts}|${prefs.numberRow}|${System.identityHashCode(layout)}"
        if (key != rowsKey) { rowsCache = build(lang, layout); rowsKey = key }
        return rowsCache
    }

    private fun build(lang: String, layout: KeyboardLayout): List<List<Key>> {
        var base = layout.rows
        if (lang == "ru" && prefs.stressMode) {
            // 重音模式: every Russian vowel flicks up to its stressed form
            base = base.map { row ->
                row.map { k ->
                    val ch = k.main?.text
                    if (ch != null && ch.length == 1 && ch[0] in VOWELS) k.copy(flicks = k.flicks + (Dir.UP to Output.of(ch + "́")))
                    else k
                }
            }
        }
        if (prefs.topRowScripts && base.isNotEmpty()) {
            // 第一排左右滑: superscript and subscript digits
            val sup = "¹²³⁴⁵⁶⁷⁸⁹⁰"; val sub = "₁₂₃₄₅₆₇₈₉₀"
            base = listOf(base[0].mapIndexed { i, k ->
                if (i < 10 && k.main != null) k.copy(flicks = k.flicks + mapOf(Dir.LEFT to Output.of(sup[i].toString()), Dir.RIGHT to Output.of(sub[i].toString())))
                else k
            }) + base.drop(1)
        }
        if (!prefs.numberRow || base.isEmpty()) return base
        val digits = (1..10).map { Key(main = Output.of((it % 10).toString())) }
        return listOf(digits) + base
    }

    /**
     * 长按选项 of a key that types something, grouped: symbols (the flick characters, the digit,
     * extra punctuation) in one row right above the key, letters (both cases, accented forms) in
     * another. Short lists stay on one row, symbols first. [rows] are the letter rows on screen.
     */
    fun longOptionRows(key: Key, panel: Panel, shift: Int, lang: String, rows: () -> List<List<Key>>): List<List<Output>> {
        val m = key.main ?: return emptyList()
        if (panel == Panel.MATH) {
            val list = DIRS.mapNotNull { key.flicks[it] }
            return if (list.isEmpty()) emptyList() else listOf(list + m)
        }
        val symbols = mutableListOf<Output>()
        val letters = mutableListOf<Output>()
        if (prefs.lpHints) DIRS.forEach { d -> key.flicks[d]?.let { symbols += it } }
        if (prefs.lpDigits && panel == Panel.LETTERS) {
            // the keys on screen (重音 and 上下标 put copies of the layout's keys there), and their
            // top letter row, below the digits when 数字行 is on
            val shown = rows()
            val top = if (prefs.numberRow && shown.size > 1) shown[1] else shown.firstOrNull()
            val col = top?.indexOfFirst { it === key } ?: -1
            if (col in 0..9) symbols += Output.of(((col + 1) % 10).toString())
        }
        if (prefs.lpCase && key.isLetter) {
            val up = m.text.uppercase(); val low = m.text.lowercase()
            if (shift > 0) { letters += Output.of(low); letters += Output.of(up) } else { letters += Output.of(up); letters += Output.of(low) }
        }
        if (lang == "ru" && m.text.length == 1 && m.text[0] in VOWELS) letters += Output.of(m.text + "́")
        for (o in key.long) if (o.text.firstOrNull()?.isLetter() == true) letters += o else symbols += o
        val s = symbols.distinctBy { it.text }
        val l = letters.distinctBy { it.text }.filter { o -> s.none { it.text == o.text } }
        val all = s + l
        if (all.isEmpty() || (all.size == 1 && all[0].text == m.text)) return emptyList()
        return if (s.isEmpty() || l.isEmpty() || all.size <= 5) listOf(all) else listOf(s, l)
    }

    companion object {
        val DIRS = listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT)
        const val VOWELS = "аеёиоуыэюя"
    }
}
