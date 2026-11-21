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
            words = TsvIndex(context.assets.open("data/${lang}_words.txt").use { it.readBytes() })
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
            val base = v.toIntOrNull() ?: 1
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
        return list.take(max).map { matchCase(it, typed) }
    }

    /** What may come after [context] (oldest first; empty or ending a sentence: a sentence start). */
    fun following(context: List<String>, capital: Boolean, max: Int = 12): List<String> {
        if (words == null) return emptyList()
        val out = LinkedHashMap<String, Double>()
        val follows = followers(context)
        follows.forEachIndexed { i, (word, weight) -> out[word] = (out[word] ?: 0.0) + weight - i * 0.01 }
        return out.entries.sortedByDescending { it.value }.map { it.key }.take(max)
            .map { if (capital) it.replaceFirstChar { c -> c.uppercase() } else it }
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

    /** [word] was written after [context] (oldest first). */
    fun learn(context: List<String>, word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > 30) return
        synchronized(this) {
            used[w] = (used[w] ?: 0) + 1
            val prev = context.lastOrNull()?.lowercase() ?: "^"
            after.getOrPut(prev) { HashMap() }.let { it[w] = (it[w] ?: 0) + 1 }
            if (used.size > MAX_WORDS) trim()
            dirty = true
        }
    }

    /** Forgets everything learned. */
    fun clearLearned() {
        synchronized(this) { used.clear(); after.clear(); dirty = false }
        Background.run { file.delete() }
    }

    /** Writes what was learned, if anything changed (called when the keyboard hides). */
    fun save() {
        val text = synchronized(this) {
            if (!dirty) return
            dirty = false
            buildString {
                for ((w, n) in used) append("w\t").append(w).append('\t').append(n).append('\n')
                for ((p, m) in after) for ((w, n) in m) append("b\t").append(p).append('\t').append(w).append('\t').append(n).append('\n')
            }
        }
        Background.run {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    private fun readLearned() {
        if (!file.exists()) return
        synchronized(this) {
            file.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 3 && p[0] == "w" -> used[p[1]] = p[2].toIntOrNull() ?: 1
                    p.size == 4 && p[0] == "b" -> after.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: 1
                }
            }
        }
    }

    /** Keeps the most used words (and the pairs that lead to them) when the memory grows big. */
    private fun trim() {
        val keep = used.entries.sortedByDescending { it.value }.take(MAX_WORDS * 3 / 4).map { it.key }.toHashSet()
        used.keys.retainAll(keep)
        after.keys.retainAll(keep + "^")
        for (m in after.values) m.keys.retainAll(keep)
    }

    companion object {
        const val MAX_WORDS = 6000
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WordPredictor, Boolean>())

        /** Forgets everything learned in every language (the settings' button). */
        fun clearAll(context: Context) {
            synchronized(live) { live.toList() }.forEach { it.clearLearned() }
            for (l in LANGS) File(context.filesDir, "learned_$l.tsv").delete()
        }
        /** Languages that have tables. */
        val LANGS = setOf("ru")

        /** The word's case follows what was typed: Привет for Пр…, ПРИВЕТ for ПР… */
        fun matchCase(word: String, typed: String): String = when {
            typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        /** A letter that can be part of a word (Cyrillic or Latin, and a hyphen inside one). */
        fun isWordChar(c: Char) = c.isLetter() || c == '-'

        /**
         * Splits the text before the caret: the word being typed (letters right before the caret)
         * and up to two whole words before it, oldest first. A sentence end or other punctuation
         * between words breaks the context.
         */
        fun split(before: String): Pair<String, List<String>> {
            var i = before.length
            while (i > 0 && isWordChar(before[i - 1])) i--
            val typed = before.substring(i).trimStart('-')
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
