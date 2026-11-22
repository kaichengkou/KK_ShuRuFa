package com.kk.shurufa.ime

import android.content.Context
import java.io.File
import kotlin.math.ln

/**
 * 俄语联想: for a language written in words (Russian), completes the word being typed and,
 * after a space, suggests the next one from the one or two words before it.
 *
 * Two committed tables (see scripts/gen_predict_ru.py), searched in place like the Chinese ones:
 * `<lang>_words.txt` (word → how common, 1..250) and `<lang>_next.txt` (one word, two words or
 * ^ for a sentence start → what follows, best first). On top of them it learns what this person
 * writes: words they use (also ones the table lacks) and which word they put after which. That
 * memory is a small file in the app's own storage and never leaves the phone.
 */
class WordPredictor(private val context: Context, val lang: String) {
    @Volatile private var words: TsvIndex? = null
    @Volatile private var next: TsvIndex? = null

    /** Learned: word → times used; "prev word" → (next word → times). */
    private val used = HashMap<String, Int>()
    private val after = HashMap<String, HashMap<String, Int>>()
    /** How this person writes a word when it is not all lowercase: names (Андрей), abbreviations (МГУ). */
    private val forms = HashMap<String, String>()
    /** The most common words of the table, for when nothing else fits. */
    @Volatile private var common: List<String> = emptyList()
    private val file get() = File(context.filesDir, "learned_$lang.tsv")
    private var dirty = false

    val ready get() = words != null

    init { synchronized(live) { live += this } }

    fun load() {
        if (words != null) return
        Background.run {
            if (words != null) return@run
            next = TsvIndex(context.assets.open("data/${lang}_next.txt").use { it.readBytes() })
            readLearned()
            val bytes = context.assets.open("data/${lang}_words.txt").use { it.readBytes() }
            common = mostCommon(bytes, 10)
            words = TsvIndex(bytes)
        }
    }

    // ---- what to offer ----

    /**
     * Words that start with [typed] (the letters right before the caret), best first. A typed
     * word that is itself a word comes first, so a finished word can be confirmed with a tap.
     * The words before it ([context], oldest first) favour what usually follows them.
     */
    fun complete(typed: String, context: List<String>, max: Int = 12): List<String> {
        val w = words ?: return emptyList()
        val low = typed.lowercase()
        if (low.isEmpty()) return emptyList()
        val follows = followers(context)
        val score = HashMap<String, Double>()
        w.prefix(low, 4000) { k, v ->
            val base = v.substringBefore('\t').toIntOrNull() ?: 1
            score[k] = base + bonus(k, follows)
        }
        // what this person writes, also words the table does not have: a word used a few times
        // outranks rare dictionary words
        for ((k, n) in used) if (k.startsWith(low)) score[k] = (score[k] ?: (100.0 + bonus(k, follows))) + 10 + 35 * ln(1.0 + n)
        val ranked = score.entries.filter { it.key != low }.sortedByDescending { it.value }
        // the typed letters as a word of their own go first only when about as likely as the best
        // completion (как, but not the rare мо while 'могу' is meant)
        val own = score[low]
        val first = own != null && own >= (ranked.firstOrNull()?.value ?: 0.0) - 12
        val list = (if (first) listOf(low) else emptyList()) + ranked.map { it.key } + (if (own != null && !first) listOf(low) else emptyList())
        return list.take(max).map { show(it, typed) }
    }

    /** A word as it is shown: in the form this person writes it (Андрей, МГУ), else in the case typed. */
    private fun show(word: String, typed: String): String {
        val f = forms[word] ?: tableForm(word)
        val allCaps = typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() }
        return if (f != null && !allCaps) f else matchCase(word, typed)
    }

    /** What may come after [context] (oldest first; empty or ending a sentence: a sentence start). */
    fun following(context: List<String>, capital: Boolean, max: Int = 12): List<String> {
        if (words == null) return emptyList()
        val out = LinkedHashMap<String, Double>()
        val follows = followers(context)
        follows.forEachIndexed { i, (word, weight) -> out[word] = (out[word] ?: 0.0) + weight - i * 0.01 }
        // never nothing: after a word the tables do not know (a name), the words this person uses
        // most, then the most common ones
        if (out.size < max) {
            synchronized(this) { used.entries.sortedByDescending { it.value }.take(6).map { it.key } }
                .forEachIndexed { i, w -> if (w !in out) out[w] = 40.0 - i }
            common.forEachIndexed { i, w -> if (w !in out) out[w] = 20.0 - i }
        }
        return out.entries.sortedByDescending { it.value }.map { it.key }.take(max)
            .map { w -> forms[w] ?: tableForm(w) ?: if (capital) w.replaceFirstChar { c -> c.uppercase() } else w }
    }

    // ---- 自动纠错 ----

    /** Words put back after a correction (backspace or the 原 chip): never corrected again. */
    private val kept = HashSet<String>()

    private fun score(word: String): Int? = words?.get(word)?.substringBefore('\t')?.toIntOrNull()

    /**
     * 自动纠错, on purpose cautious: what [typed] (a word just finished with a space or punctuation)
     * should become, or null to leave it. It corrects only
     *  - a word the table does not have that is one slip (a neighbouring key, two letters swapped,
     *    a letter doubled or missed) away from a common word, and clearly the likeliest one
     *    (teh → the, рпивет → привет);
     *  - in English, I and its contractions (i → I, im → I'm, dont → don't).
     * A word the table has stays, however rare: Басков, взяло and aloe are words too.
     * Never a word this person has used (or written in their own form), a word put back
     * after a correction, a capitalised word inside a sentence (a name), or capitals elsewhere (МГУ).
     */
    fun correction(typed: String, sentenceStart: Boolean): String? {
        if (words == null || typed.isEmpty() || typed.length > 24) return null
        if (!typed.all { isWordChar(it) } || typed.drop(1).any { it.isUpperCase() }) return null
        if (typed[0].isUpperCase() && !sentenceStart) return null
        val low = typed.lowercase()
        synchronized(this) { if (low in kept || used.containsKey(low) || forms.containsKey(low)) return null }
        val own = score(low)
        if (lang == "en") {
            tableForm(low)?.let { f -> if (low.startsWith("i") && (f.length == 1 || f[1] == '\'') && f.startsWith("I")) return f.takeIf { it != typed } }
            if (own != null && own < 140) for (i in 1 until low.length) {
                val c = low.substring(0, i) + "'" + low.substring(i)
                if (!c.contains("n't") && !c.startsWith("i'")) continue
                val sc = score(c) ?: continue
                if (sc >= own + 60) return show(c, typed)
            }
        }
        // a word the table has is a word, however rare (Басков, взяло, aloe): never replaced
        if (own != null || low.length < 3) return null
        val floor = if (low.length == 3) 150 else 100
        val found = HashMap<String, Int>()
        for (c in slips(low)) { if (c == low) continue; val sc = score(c) ?: continue; if (sc >= floor) found[c] = sc }
        val ranked = found.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        if (ranked.size > 1 && ranked[1].value > best.value - 25) return null
        return show(best.key, typed)
    }

    /** The word was put back after a correction: it stays as written from now on. */
    fun keep(word: String, learn: Boolean) {
        val w = word.lowercase()
        synchronized(this) {
            kept += w
            if (learn) { used[w] = maxOf(2, (used[w] ?: 0) + 1); dirty = true }
        }
    }

    /** Every word one slip away from [w]: a neighbouring key, two letters swapped, one too many, one missed. */
    private fun slips(w: String): Sequence<String> = sequence {
        val near = NEAR.getValue(lang)
        val letters = ALPHABET.getValue(lang)
        for (i in w.indices) {
            near[w[i]]?.forEach { c -> yield(w.substring(0, i) + c + w.substring(i + 1)) }
            if (i + 1 < w.length && w[i] != w[i + 1]) yield(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            // one too many: a letter pressed twice or a neighbour caught with it
            val prev = w.getOrNull(i - 1); val next = w.getOrNull(i + 1)
            if (w.length > 3 && (w[i] == prev || w[i] == next || near[w[i]]?.let { n -> (prev != null && prev in n) || (next != null && next in n) } == true))
                yield(w.removeRange(i, i + 1))
        }
        // one missed (only in longer words: in short ones almost anything is a letter away)
        if (w.length >= 4) for (i in 1..w.length) for (c in letters) yield(w.substring(0, i) + c + w.substring(i))
    }

    /** How the table says a word is written inside a sentence, when not in lowercase (Москва, I, Tom). */
    private fun tableForm(word: String): String? = words?.get(word)?.substringAfter('\t', "")?.takeIf { it.isNotEmpty() }

    /** The [n] best-scored words of a words table. */
    private fun mostCommon(bytes: ByteArray, n: Int): List<String> {
        val best = java.util.PriorityQueue<Pair<String, Int>>(compareBy { it.second })
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] != '\n'.code.toByte()) continue
            val line = String(bytes, start, i - start, Charsets.UTF_8)
            start = i + 1
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val sc = line.substring(tab + 1).substringBefore('\t').toIntOrNull() ?: continue
            best.add(line.substring(0, tab) to sc)
            if (best.size > n) best.poll()
        }
        return best.sortedByDescending { it.second }.map { it.first }
    }

    /** Candidates for what follows [context], with weights: learned pairs first, then the tables. */
    private fun followers(context: List<String>): List<Pair<String, Double>> {
        val n = next ?: return emptyList()
        val ctx = context.map { it.lowercase() }
        val out = ArrayList<Pair<String, Double>>()
        val last = ctx.lastOrNull() ?: "^"
        after[last]?.entries?.sortedByDescending { it.value }?.take(6)?.forEach { (w, c) -> out += w to 120.0 + 30 * ln(1.0 + c) }
        if (ctx.size >= 2) n[ctx.takeLast(2).joinToString(" ")]?.split(' ')?.forEachIndexed { i, w -> out += w to 100.0 - i * 6 }
        n[last]?.split(' ')?.forEachIndexed { i, w -> out += w to 80.0 - i * 5 }
        return out
    }

    private fun bonus(word: String, follows: List<Pair<String, Double>>): Double =
        follows.firstOrNull { it.first == word }?.second ?: 0.0

    // ---- learning ----

    /**
     * [word] was written after [context] (oldest first). [sentenceStart]: it opened a sentence, so a
     * capital says nothing about the word itself.
     */
    fun learn(context: List<String>, word: String, sentenceStart: Boolean = false) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > 30) return
        synchronized(this) {
            used[w] = (used[w] ?: 0) + 1
            // the written form: a capital inside a sentence (Леонидович) or capitals throughout (МГУ)
            // is how the word is written; a capital opening a sentence only for a word the table lacks
            if (word != w) {
                val caps = word.length >= 2 && word.all { !it.isLetter() || it.isUpperCase() }
                if (!sentenceStart || caps || (words?.get(w) == null && forms[w] == null)) {
                    if (word != tableForm(w)) forms[w] = word else forms.remove(w)
                }
            } else if (!sentenceStart) forms.remove(w)
            val prev = context.lastOrNull()?.lowercase() ?: "^"
            after.getOrPut(prev) { HashMap() }.let { it[w] = (it[w] ?: 0) + 1 }
            if (used.size > MAX_WORDS) trim()
            dirty = true
        }
    }

    /** Forgets everything learned. */
    fun clearLearned() {
        synchronized(this) { used.clear(); after.clear(); forms.clear(); dirty = false }
        Background.run { file.delete() }
    }

    /** Writes what was learned, if anything changed (called when the keyboard hides). */
    fun save() {
        val text = synchronized(this) {
            if (!dirty) return
            dirty = false
            buildString {
                append("t\t").append(agedAt).append('\n')
                for ((w, n) in used) append("w\t").append(w).append('\t').append(n).append('\n')
                for ((w, f) in forms) append("f\t").append(w).append('\t').append(f).append('\n')
                for ((p, m) in after) for ((w, n) in m) append("b\t").append(p).append('\t').append(w).append('\t').append(n).append('\n')
            }
        }
        Background.run {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    /** The month (yyyy-MM) the memory was last aged. */
    private var agedAt = java.time.YearMonth.now().toString()

    private fun readLearned() {
        if (!file.exists()) return
        synchronized(this) {
            var stamp: String? = null
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 2 && p[0] == "t" -> stamp = p[1]
                    p.size == 3 && p[0] == "w" -> used[p[1]] = p[2].toIntOrNull() ?: 1
                    p.size == 3 && p[0] == "f" -> forms[p[1]] = p[2]
                    p.size == 4 && p[0] == "b" -> after.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: 1
                }
            }
            // 慢慢忘记: every three months what was learned counts half, so a name typed once last
            // year fades while the words in daily use stay
            val then = stamp?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() }
            val now = java.time.YearMonth.now()
            if (then == null) { agedAt = now.toString(); dirty = true }
            else {
                val quarters = (java.time.temporal.ChronoUnit.MONTHS.between(then, now) / AGE_MONTHS).toInt()
                if (quarters > 0) { age(quarters); agedAt = then.plusMonths(quarters.toLong() * AGE_MONTHS).toString(); dirty = true }
                else agedAt = then.toString()
            }
        }
    }

    /** Halves every count [times] times; what reaches nothing is forgotten (with its written form). */
    private fun age(times: Int) {
        val shift = times.coerceAtMost(30)
        used.replaceAll { _, n -> n shr shift }
        used.values.removeAll { it == 0 }
        forms.keys.retainAll(used.keys)
        for (m in after.values) { m.replaceAll { _, n -> n shr shift }; m.values.removeAll { it == 0 } }
        after.values.removeAll { it.isEmpty() }
    }

    /** Keeps the most used words (and the pairs that lead to them) when the memory grows big. */
    private fun trim() {
        val keep = used.entries.sortedByDescending { it.value }.take(MAX_WORDS * 3 / 4).map { it.key }.toHashSet()
        used.keys.retainAll(keep)
        forms.keys.retainAll(keep)
        after.keys.retainAll(keep + "^")
        for (m in after.values) m.keys.retainAll(keep)
    }

    companion object {
        const val MAX_WORDS = 6000
        /** How often learned counts are halved. */
        const val AGE_MONTHS = 3
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WordPredictor, Boolean>())

        /** Forgets everything learned in every language (the settings' button). */
        fun clearAll(context: Context) {
            synchronized(live) { live.toList() }.forEach { it.clearLearned() }
            for (l in LANGS) File(context.filesDir, "learned_$l.tsv").delete()
        }
        /** Keys next to each key on the layouts (ЙЦУКЕН, QWERTY), rows offset half a key. */
        private val ROWS = mapOf(
            "ru" to listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
            "en" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        )
        private val ALPHABET = ROWS.mapValues { (_, r) -> r.joinToString("") }
        private val NEAR: Map<String, Map<Char, String>> = ROWS.mapValues { (_, rows) ->
            val m = HashMap<Char, StringBuilder>()
            fun link(a: Char, b: Char) { m.getOrPut(a) { StringBuilder() }.append(b); m.getOrPut(b) { StringBuilder() }.append(a) }
            for ((r, row) in rows.withIndex()) for ((i, c) in row.withIndex()) {
                row.getOrNull(i + 1)?.let { link(c, it) }
                // the row below sits half a key to the right: under c are its i-1 and i
                rows.getOrNull(r + 1)?.let { below -> below.getOrNull(i)?.let { link(c, it) }; below.getOrNull(i - 1)?.let { link(c, it) } }
            }
            m.mapValues { it.value.toString() }
        }

        /** Languages that have tables. */
        val LANGS = setOf("ru", "en")

        /** The word's case follows what was typed: Привет for Пр…, ПРИВЕТ for ПР… */
        fun matchCase(word: String, typed: String): String = when {
            typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        /**
         * A letter that can be part of a word: Latin, Cyrillic or Greek, and a hyphen or apostrophe
         * inside one. Not Chinese, so 这是Андрей ends the context at А.
         */
        fun isWordChar(c: Char): Boolean {
            if (c == '-' || c == '\'' || c == '’') return true
            if (!c.isLetter()) return false
            val s = Character.UnicodeScript.of(c.code)
            return s == Character.UnicodeScript.LATIN || s == Character.UnicodeScript.CYRILLIC || s == Character.UnicodeScript.GREEK
        }

        /**
         * Splits the text before the caret: the word being typed (letters right before the caret)
         * and up to two whole words before it, oldest first. A sentence end or other punctuation
         * between words breaks the context.
         */
        fun split(before: String): Pair<String, List<String>> {
            var i = before.length
            while (i > 0 && isWordChar(before[i - 1])) i--
            val typed = before.substring(i).trimStart('-', '\'', '’')
            val rest = before.substring(0, i)
            val ctx = ArrayList<String>()
            var j = rest.length
            for (n in 0 until 2) {
                var k = j
                while (k > 0 && rest[k - 1] == ' ') k--
                if (k == j && n == 0 && j > 0) break           // not a space before the word: no context
                val end = k
                while (k > 0 && isWordChar(rest[k - 1])) k--
                if (k == end) break
                ctx.add(0, rest.substring(k, end).trim('-'))
                j = k
            }
            return typed to ctx
        }

        /** Whether the text before the caret ends a sentence (or is empty): the next word is capitalised. */
        fun sentenceStart(before: String): Boolean {
            val t = before.trimEnd()
            return t.isEmpty() || t.last() in ".!?…\n"
        }
    }
}
