package com.kk.shurufa.rime

import android.content.Context
import com.kk.shurufa.voice.VoiceModels
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 语法模型: the 万象 (wanxiang) model for librime-octagram. With it the engine weighs how words go
 * together, not only how common each one is, so whole sentences come out right far more often.
 * It is about 390 MB, so it is an optional download; the engine maps it from disk rather than
 * reading it into memory.
 */
object Grammar {
    const val NAME = "wanxiang-lts-zh-hans"
    private const val URL = "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/$NAME.gram"
    const val SIZE_MB = 390

    fun file(ctx: Context) = File(RimeEngine.userDir(ctx), "$NAME.gram")
    fun installed(ctx: Context) = file(ctx).isFile

    /** Downloads the model (resuming a partial one). Blocking. */
    fun download(ctx: Context, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        RimeEngine.userDir(ctx).mkdirs()
        VoiceModels.fetch(URL, file(ctx), progress, cancelled)
    }

    fun delete(ctx: Context) { file(ctx).delete(); File(file(ctx).path + ".part").delete() }

    /** The schema patch that turns the model on (the settings 雾凇拼音 recommends for it). */
    val patch = """
        |  grammar/language: $NAME
        |  grammar/collocation_max_length: 6
        |  grammar/collocation_min_length: 3
        |  grammar/collocation_penalty: -14
        |  grammar/non_collocation_penalty: -6
        |  grammar/weak_collocation_penalty: -100
        |  grammar/rear_penalty: -20
        |  translator/contextual_suggestions: false
        |  translator/max_homophones: 8
        |""".trimMargin()
}

/**
 * 导入词库: word lists the user adds (搜狗细胞词库 .scel, or a text list), converted into Rime
 * dictionaries in the user folder. They are mounted after 雾凇拼音's own tables in one merged
 * dictionary, so their words join whole sentences like any other, and words given without pinyin
 * are spelled by Rime from its character table.
 *
 * Rime does not let the first table win when a word is in two of them (an import with a huge
 * weight moved 塔门 above 她们 in testing), so imported weights are capped at [MAX_WEIGHT], about
 * twice the median of 雾凇's base dictionary: far below any everyday word, so an import adds words
 * without pushing common ones down.
 */
object ImportedDicts {
    private const val PREFIX = "kk_import_"
    /** The weight an imported word gets: around the median of 雾凇's base dictionary. */
    const val WEIGHT = 400
    /** The most an imported word may weigh, however its list ranks it. */
    const val MAX_WEIGHT = 800

    /** An imported dictionary; [source] is the pack it came from (a catalog id or a link), if any. */
    class Dict(val id: String, val title: String, val words: Int, val file: File, val source: String?)

    fun list(ctx: Context): List<Dict> = RimeEngine.userDir(ctx).listFiles()
        ?.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".dict.yaml") }
        ?.sortedBy { it.lastModified() }
        ?.map { f ->
            val head = f.bufferedReader().use { r -> generateSequence { r.readLine() }.take(8).toList() }
            fun meta(k: String) = head.firstOrNull { it.startsWith("# $k: ") }?.substringAfter(": ")
            Dict(f.name.removeSuffix(".dict.yaml"), meta("title") ?: f.name, meta("words")?.toIntOrNull() ?: 0, f, meta("source"))
        }.orEmpty()

    fun delete(d: Dict) { d.file.delete() }

    class Entry(val text: String, val code: String?, val weight: Int)

    /**
     * Converts [bytes] (a .scel file, or text) into a dictionary named after [title]. Returns the
     * number of words; throws with a readable message when nothing usable is in it.
     */
    fun import(ctx: Context, title: String, bytes: ByteArray, source: String? = null): Int {
        val entries = if (Scel.isScel(bytes)) Scel.parse(bytes) else Text.parse(bytes.toString(Charsets.UTF_8))
        if (entries.isEmpty()) error("没有读到词条：支持搜狗细胞词库（.scel）和每行一个词的文本")
        // installing a pack again replaces it
        if (source != null) list(ctx).filter { it.source == source }.forEach { delete(it) }
        val dir = RimeEngine.userDir(ctx).apply { mkdirs() }
        // a fresh name, even for two imports in the same millisecond
        var t = System.currentTimeMillis()
        while (File(dir, "$PREFIX${t.toString(36)}.dict.yaml").exists()) t++
        val id = PREFIX + t.toString(36)
        write(File(dir, "$id.dict.yaml"), id, title, entries, source)
        return entries.size
    }

    /** Writes a Rime dictionary. Words without a spelling are spelled by Rime from its character table. */
    fun write(f: File, id: String, title: String, entries: List<Entry>, source: String? = null) {
        val seen = HashSet<String>()
        f.bufferedWriter().use { w ->
            w.write("# title: ${title.replace('\n', ' ')}\n# words: ${entries.size}\n")
            if (source != null) w.write("# source: ${source.replace('\n', ' ')}\n")
            w.write("# Imported by KK输入法.\n---\nname: $id\nversion: \"1\"\nsort: by_weight\n...\n\n")
            for (e in entries) {
                if (!seen.add(e.text + "\t" + e.code)) continue
                w.write(e.text); w.write("\t")
                if (e.code != null) w.write(e.code)
                w.write("\t"); w.write(e.weight.toString()); w.write("\n")
            }
        }
    }

    /**
     * The merged dictionary: 雾凇拼音's own tables (as its rime_ice.dict.yaml lists them, the
     * updated one first) and then every import. Null when nothing is imported.
     */
    fun merged(ctx: Context, shared: File, user: File): String? {
        val imports = list(ctx)
        if (imports.isEmpty()) return null
        val main = File(user, "rime_ice.dict.yaml").takeIf { it.isFile } ?: File(shared, "rime_ice.dict.yaml")
        val tables = mainTables(main.readText())
        return buildString {
            append("# Generated by KK输入法: 雾凇拼音's tables, then the imported ones. Edits are overwritten.\n")
            append("---\nname: kk_merged\nversion: \"").append(imports.joinToString(",") { it.id }.hashCode().toUInt()).append("\"\n")
            append("sort: by_weight\nimport_tables:\n  - rime_ice\n")
            for (t in tables) append("  - ").append(t).append('\n')
            for (d in imports) append("  - ").append(d.id).append('\n')
            append("...\n")
        }
    }

    /** The tables a dictionary file imports (its import_tables list, comments dropped). */
    fun mainTables(yaml: String): List<String> {
        val out = mutableListOf<String>()
        var inList = false
        for (raw in yaml.lineSequence()) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.startsWith("import_tables:")) { inList = true; continue }
            if (!inList) continue
            if (line.isBlank()) continue
            val t = line.trim()
            if (!t.startsWith("- ")) break
            out += t.removePrefix("- ").trim()
        }
        return out
    }

    /** Patch lines that switch the main translator to the merged dictionary, keeping what it learned. */
    const val PATCH = "  translator/dictionary: kk_merged\n  translator/user_dict: rime_ice\n"

    /** 搜狗细胞词库: a binary list of words with their pinyin. */
    object Scel {
        private val MAGIC = byteArrayOf(0x40, 0x15, 0, 0)

        fun isScel(b: ByteArray) = b.size > 0x2628 && b.copyOfRange(0, 4).contentEquals(MAGIC)

        /** The name the dictionary gives itself. */
        fun title(b: ByteArray): String = String(b, 0x130, 0x338 - 0x130, Charsets.UTF_16LE).substringBefore('\u0000').trim()

        fun parse(b: ByteArray): List<Entry> {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            fun str(pos: Int, len: Int) = String(b, pos, len, Charsets.UTF_16LE)
            // the syllable table: index -> pinyin
            val py = HashMap<Int, String>()
            var p = 0x1540 + 4
            while (p + 4 <= 0x2628) {
                val idx = buf.getShort(p).toInt() and 0xffff
                val len = buf.getShort(p + 2).toInt() and 0xffff
                if (len == 0 || p + 4 + len > 0x2628) break
                py[idx] = str(p + 4, len)
                p += 4 + len
            }
            // the words: groups that share one spelling
            val out = ArrayList<Entry>()
            p = 0x2628
            while (p + 4 <= b.size) {
                val same = buf.getShort(p).toInt() and 0xffff
                val pyLen = buf.getShort(p + 2).toInt() and 0xffff
                p += 4
                if (same == 0 || p + pyLen > b.size) break
                val code = (0 until pyLen / 2).map { py[buf.getShort(p + it * 2).toInt() and 0xffff] ?: "" }
                p += pyLen
                for (k in 0 until same) {
                    if (p + 2 > b.size) return out
                    val wl = buf.getShort(p).toInt() and 0xffff; p += 2
                    if (p + wl + 2 > b.size) return out
                    val word = str(p, wl); p += wl
                    val extLen = buf.getShort(p).toInt() and 0xffff; p += 2 + extLen
                    if (word.isNotBlank() && code.none { it.isEmpty() } && code.size == word.length)
                        out += Entry(word, code.joinToString(" "), WEIGHT)
                }
            }
            return out
        }
    }

    /**
     * Text lists: one word a line, optionally with its pinyin and a weight, separated by tabs or
     * spaces ("词", "词 ci", "词\tci\t100", "词\t100"). Weights are scaled so the list's median
     * lands on [WEIGHT] and capped at [MAX_WEIGHT]: a list's own ranking is kept, its scale is not.
     */
    object Text {
        private val HAN = Regex("[\\p{IsHan}]+")
        private val SPELL = Regex("^[a-z]+( [a-z]+)*$")

        fun parse(s: String): List<Entry> {
            val raw = ArrayList<Triple<String, String?, Int?>>()
            for (line0 in s.lineSequence()) {
                val line = line0.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith("#") || line == "---" || line == "...") continue
                if (line.contains(':') && !line.contains('\t')) continue  // a yaml header line
                val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                val cols = if (parts.size > 1) parts else line.split(Regex("\\s+"))
                val word = cols[0]
                if (!HAN.matches(word)) continue
                var code: String? = null; var weight: Int? = null
                val rest = cols.drop(1)
                for (c in rest) {
                    when {
                        c.toIntOrNull() != null -> weight = c.toInt()
                        SPELL.matches(c.lowercase()) && code == null -> code = c.lowercase()
                    }
                }
                // pinyin written without tabs ("词 ci yu") comes in as several columns
                if (parts.size <= 1 && rest.isNotEmpty() && rest.all { SPELL.matches(it.lowercase()) }) code = rest.joinToString(" ") { it.lowercase() }
                if (code != null && code.split(' ').size != word.length) code = null
                raw += Triple(word, code, weight)
            }
            val weights = raw.mapNotNull { it.third }.filter { it > 0 }.sorted()
            val median = weights.getOrNull(weights.size / 2)?.coerceAtLeast(1)
            return raw.map { (w, c, wt) ->
                val scaled = if (wt != null && median != null) (wt.toLong() * WEIGHT / median).toInt().coerceIn(1, MAX_WEIGHT) else WEIGHT
                Entry(w, c, scaled)
            }
        }
    }
}

/**
 * 词库拓展包: catalogs of dictionaries that install with one tap. One is built in (搜狗's
 * officially recommended cell dictionaries, by subject); more can be subscribed to by link. A
 * catalog is JSON:
 *
 *     {"name": "…", "note": "…", "packs": [
 *       {"id": "unique-id", "group": "分类", "name": "名字", "words": 1234, "url": "https://…"}
 *     ]}
 *
 * where url points at a .scel, a text list or a Rime .dict.yaml; an id "sogou:<n>" needs no url.
 * Installed packs are imported dictionaries (see [ImportedDicts]) that remember their id.
 */
object DictPacks {
    class Pack(val id: String, val group: String, val name: String, val words: Int, val url: String)
    class Catalog(val name: String, val note: String, val packs: List<Pack>, val source: String)

    private const val MAX_BYTES = 40 * 1024 * 1024

    fun parse(json: String, source: String): Catalog {
        val o = org.json.JSONObject(json)
        val a = o.getJSONArray("packs")
        val packs = (0 until a.length()).mapNotNull { i ->
            val p = a.getJSONObject(i)
            val id = p.optString("id").ifEmpty { return@mapNotNull null }
            val url = p.optString("url").ifEmpty { sogouUrl(id) ?: return@mapNotNull null }
            Pack(id, p.optString("group", "其他"), p.optString("name", id), p.optInt("words", 0), url)
        }
        return Catalog(o.optString("name", source), o.optString("note", ""), packs, source)
    }

    fun builtIn(ctx: Context): Catalog = parse(ctx.assets.open("data/dict_packs.json").bufferedReader().use { it.readText() }, "builtin")

    private fun sogouUrl(id: String): String? = id.removePrefix("sogou:").takeIf { id.startsWith("sogou:") && it.all(Char::isDigit) }
        ?.let { "https://pinyin.sogou.com/d/dict/download_cell.php?id=$it&name=kk" }

    /**
     * What a pasted link downloads: a 搜狗 dictionary page (…/dict/detail/index/15117) becomes its
     * download; anything else on http(s) is taken as is.
     */
    fun resolve(link: String): String? {
        val t = link.trim()
        Regex("pinyin\\.sogou\\.com/dict/detail/index/(\\d+)").find(t)?.let { return sogouUrl("sogou:" + it.groupValues[1]) }
        return t.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** Downloads [url] (at most 40 MB). Blocking. */
    fun download(url: String): ByteArray {
        var u = java.net.URL(url)
        repeat(6) {
            val c = u.openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "KK-IME")
            try {
                val code = c.responseCode
                if (code in 300..399) { u = java.net.URL(u, c.getHeaderField("Location")); return@repeat }
                if (code !in 200..299) error("HTTP $code")
                if (c.contentLengthLong > MAX_BYTES) error("文件太大")
                return c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) error("文件太大")
                    }
                    out.toByteArray()
                }
            } finally { c.disconnect() }
        }
        error("跳转太多")
    }

    /** Downloads and imports [pack]; the number of words. Blocking. */
    fun install(ctx: Context, pack: Pack): Int = ImportedDicts.import(ctx, pack.name, download(pack.url), source = pack.id)

    /** Subscribed catalog links, kept in the settings. */
    fun subscriptions(prefs: com.kk.shurufa.Prefs): List<String> =
        prefs.dictFeeds.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun setSubscriptions(prefs: com.kk.shurufa.Prefs, list: List<String>) = prefs.put(com.kk.shurufa.Prefs.DICT_FEEDS, list.distinct().joinToString("\n"))

    /** Reads a subscribed catalog. Blocking. */
    fun fetch(url: String): Catalog = parse(download(url).toString(Charsets.UTF_8), url)
}
