package com.kk.shurufa.ime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Emoji groups and their names (CLDR keywords in Chinese and English) for the emoji panel and
 * 查找. The APK ships a copy; [update] fetches the newest Unicode list and CLDR names and keeps
 * them in the app's files, used from then on when they are newer than the shipped copy.
 * Emoji the phone's font cannot draw are hidden by the panel, so a newer list only shows what
 * the system can actually display (and what other apps will show).
 */
class EmojiData(private val context: Context) {
    class Item(val emoji: String, val zh: List<String>, val en: List<String>)

    var version = ""
        private set
    var groups: List<Pair<String, List<String>>> = emptyList()
        private set
    private var names: Map<String, Item> = emptyMap()
    /** Skin-tone and man/woman forms of an emoji, for its long-press menu. */
    var variants: Map<String, List<String>> = emptyMap()
        private set

    private val dir get() = File(context.filesDir, "emoji")

    fun load() {
        val shipped = runCatching { asset("emoji_version.txt").trim() }.getOrDefault("")
        val local = File(dir, "emoji_version.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val useLocal = local.isNotEmpty() && newer(local, shipped) && listOf("emoji.txt", "emoji_names.txt", "emoji_variants.txt").all { File(dir, it).isFile }
        fun read(name: String) = if (useLocal) File(dir, name).readText() else asset(name)
        version = if (useLocal) local else shipped
        groups = parseGroups(read("emoji.txt"))
        variants = parseGroups(read("emoji_variants.txt")).toMap()
        names = read("emoji_names.txt").lineSequence().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else Item(p[0], p[1].split('|').filter { it.isNotEmpty() }, p[2].split('|').filter { it.isNotEmpty() })
        }.associateBy { it.emoji }
        // what 查找 compares against, prepared once: lowercased keywords, the panel's order
        index = names.values.map { it.emoji to (it.zh + it.en).map { w -> w.lowercase() }.toTypedArray() }
        own = names.values.associate { it.emoji to setOfNotNull(it.zh.firstOrNull()?.lowercase(), it.en.firstOrNull()?.lowercase()) }
        val o = HashMap<String, Int>()
        var n = 0
        for ((_, list) in groups) for (e in list) o[e] = n++
        order = o
    }

    private var index: List<Pair<String, Array<String>>> = emptyList()
    private var own: Map<String, Set<String>> = emptyMap()
    private var order: Map<String, Int> = emptyMap()

    private fun asset(name: String) = context.assets.open("data/$name").bufferedReader().readText()

    /**
     * Emoji whose names match [query]. A name equal to the query ranks first, then names that
     * start with or contain it, then names found inside the query (so a phrase like 今天好开心
     * finds the 开心 faces). Within a rank the panel's own order is kept.
     */
    fun search(query: String, limit: Int = 80): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val negated = NEGATIONS.map { it + q }
        val qNegative = NEGATIONS.any { q.startsWith(it) }
        val scored = ArrayList<Pair<String, Int>>()
        for ((emoji, words) in index) {
            var best = 0
            val names = own[emoji].orEmpty()
            for (k in words) {
                // 不开心 is not a match for 开心
                if (!qNegative && negated.any { k.contains(it) }) continue
                val s = when {
                    k == q -> 100
                    k.startsWith(q) -> 60
                    q.length >= 2 && k.contains(q) -> 40
                    k.length >= 2 && q.contains(k) -> 10 + k.length * 4
                    else -> 0
                }
                // the first keyword is the emoji's own name: an exact name beats an exact keyword
                val v = if (s == 0 || k !in names) s else if (s == 100) 130 else s + 5
                if (v > best) best = v
            }
            if (best > 0) scored += emoji to best
        }
        return scored.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { order[it.first] ?: Int.MAX_VALUE })
            .take(limit).map { it.first }
    }

    companion object {
        private val NEGATIONS = listOf("不", "没", "无", "非", "un", "not ")
        private const val TEST_URL = "https://www.unicode.org/Public/emoji/latest/emoji-test.txt"
        private val CLDR = listOf("https://cdn.jsdelivr.net/npm/", "https://unpkg.com/")
        private val GROUP_NAMES = mapOf(
            "Smileys & Emotion" to "表情", "People & Body" to "人物", "Animals & Nature" to "动物", "Food & Drink" to "食物",
            "Travel & Places" to "旅行", "Activities" to "活动", "Objects" to "物品", "Symbols" to "符号", "Flags" to "旗帜",
        )

        /** Skin tones and a trailing ‍♀️/‍♂️ removed: every variant of an emoji shares this key. */
        fun baseKey(cps: List<Int>): List<Int> {
            val c = cps.filter { it !in 0x1F3FB..0x1F3FF && it != 0xFE0F }
            return if (c.size >= 3 && c[c.size - 2] == 0x200D && (c.last() == 0x2640 || c.last() == 0x2642)) c.dropLast(2) else c
        }

        fun parseGroups(text: String) = text.lineSequence().mapNotNull { l ->
            val t = l.indexOf('\t')
            if (t < 0) null else l.substring(0, t) to l.substring(t + 1).split(' ').filter { it.isNotEmpty() }
        }.toList()

        /** "16.0" < "18.0", numerically by part. */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        private fun fetch(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "KK-IME")
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().readText()
            } finally { c.disconnect() }
        }

        private fun fetchCldr(path: String): String {
            var last: Exception? = null
            for (base in CLDR) try { return fetch(base + path) } catch (e: Exception) { last = e }
            throw last ?: IllegalStateException()
        }

        /**
         * Checks for a newer emoji version and installs it. Blocking: call it off the main thread.
         * Returns the installed version, or null when the current one is already the newest.
         */
        fun update(context: Context, current: String): String? {
            val test = fetch(TEST_URL)
            val version = Regex("^# Version: *(\\S+)", RegexOption.MULTILINE).find(test)?.groupValues?.get(1) ?: error("无法识别版本")
            if (!newer(version, current)) return null
            val groups = LinkedHashMap<String, MutableList<String>>()
            val variants = LinkedHashMap<String, MutableList<String>>()
            val owner = HashMap<List<Int>, String>()
            var cur: String? = null
            for (line in test.lineSequence()) {
                if (line.startsWith("# group:")) { cur = line.substringAfter(':').trim().takeIf { it != "Component" }; continue }
                if (cur == null || line.startsWith("#") || !line.contains(';')) continue
                val (codes, rest) = line.split(';', limit = 2)
                if (!rest.contains("fully-qualified")) continue
                val cps = codes.trim().split(Regex("\\s+")).map { it.toInt(16) }
                val e = cps.joinToString("") { String(Character.toChars(it)) }
                // every skin-tone and man/woman form joins the first emoji with the same base
                val key = baseKey(cps)
                val first = owner[key]
                if (first != null) variants.getOrPut(first) { mutableListOf() } += e
                else { owner[key] = e; groups.getOrPut(cur) { mutableListOf() } += e }
            }
            fun ann(lang: String): Map<String, List<String>> {
                val m = HashMap<String, List<String>>()
                for ((pkg, key) in listOf("cldr-annotations-full@latest/annotations/" to "annotations", "cldr-annotations-derived-full@latest/annotationsDerived/" to "annotationsDerived")) {
                    val root = JSONObject(fetchCldr("$pkg$lang/annotations.json")).getJSONObject(key).getJSONObject("annotations")
                    for (k in root.keys()) {
                        val v = root.getJSONObject(k)
                        val words = mutableListOf<String>()
                        v.optJSONArray("tts")?.let { a -> for (i in 0 until a.length()) words += a.getString(i) }
                        v.optJSONArray("default")?.let { a -> for (i in 0 until a.length()) a.getString(i).let { if (it !in words) words += it } }
                        m[k.replace("️", "")] = words
                    }
                }
                return m
            }
            val zh = ann("zh"); val en = ann("en")
            val dir = File(context.filesDir, "emoji").apply { mkdirs() }
            File(dir, "emoji.txt").writeText(groups.entries.joinToString("") { (g, l) -> (GROUP_NAMES[g] ?: g) + "\t" + l.joinToString(" ") + "\n" })
            File(dir, "emoji_names.txt").writeText(buildString {
                for (l in groups.values) for (e in l) {
                    val k = e.replace("️", "")
                    val z = zh[k].orEmpty(); val n = en[k].orEmpty()
                    if (z.isNotEmpty() || n.isNotEmpty()) append(e).append('\t').append(z.joinToString("|")).append('\t').append(n.joinToString("|")).append('\n')
                }
            })
            File(dir, "emoji_variants.txt").writeText(variants.entries.joinToString("") { (e, v) -> e + "\t" + v.joinToString(" ") + "\n" })
            // written last: only a complete download counts
            File(dir, "emoji_version.txt").writeText(version + "\n")
            return version
        }
    }
}
