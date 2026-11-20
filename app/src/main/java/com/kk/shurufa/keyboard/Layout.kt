package com.kk.shurufa.keyboard

import android.content.Context
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** What a key (or one of its gestures) puts on screen. `tex` is only used by the math keyboard. */
data class Output(val label: String, val text: String, val tex: String = text) {
    companion object {
        fun of(s: String) = Output(s, s, s)
    }
}

enum class KeyStyle { NORMAL, FUNC }

class Key(
    val main: Output? = null,
    val flicks: Map<Dir, Output> = emptyMap(),
    val long: List<Output> = emptyList(),
    val fn: String? = null,
    val label: String? = null,
    val width: Float = 1f,
    val style: KeyStyle = if (fn != null && fn != "space") KeyStyle.FUNC else KeyStyle.NORMAL,
    val spacer: Boolean = false,
) {
    val isLetter get() = main != null && main.text.length == 1 && main.text[0].isLetter()
}

class KeyboardLayout(
    val id: String,
    val label: String,
    val engine: String,
    val rows: List<List<Key>>,
)

class MathTab(val id: String, val name: String, val rows: List<List<Key>>)
class MathLayout(val tabs: List<MathTab>, val bottom: List<Key>)

class SymbolCategory(val id: String, val name: String, val items: List<Output>, val wide: Boolean)

/**
 * Loads keyboard layouts from YAML. Each file in assets/layouts can be overridden by a file with
 * the same name in the app's external files folder (Android/data/com.kk.shurufa/files/layouts).
 */
class LayoutRepo(private val context: Context?, private val source: ((String) -> String)? = null) {
    val userDir: File get() = File(context!!.getExternalFilesDir(null), "layouts")

    private fun read(name: String): Any? {
        val text = source?.invoke(name) ?: run {
            val user = File(userDir, name)
            if (user.isFile) user.readText() else context!!.assets.open("layouts/$name").bufferedReader().readText()
        }
        return Load(LoadSettings.builder().build()).loadFromString(text)
    }

    /** Copies the built-in layouts to the user folder so they can be edited. Existing files are kept. */
    fun exportDefaults(): File {
        val context = context!!
        userDir.mkdirs()
        context.assets.list("layouts")?.forEach { n ->
            val f = File(userDir, n)
            if (!f.exists()) context.assets.open("layouts/$n").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return userDir
    }

    fun keyboard(id: String): KeyboardLayout {
        val m = read("$id.yaml") as Map<*, *>
        val extraLong = (m["long"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to outputs(v) } ?: emptyMap()
        val rows = (m["rows"] as List<*>).map { row -> (row as List<*>).map { parseKey(it, extraLong) } }
        return KeyboardLayout(id, m["label"]?.toString() ?: id, m["engine"]?.toString() ?: "direct", rows)
    }

    fun math(): MathLayout {
        val m = read("math.yaml") as Map<*, *>
        val tabs = (m["tabs"] as List<*>).map { t ->
            t as Map<*, *>
            MathTab(t["id"].toString(), t["name"].toString(),
                (t["rows"] as List<*>).map { r -> (r as List<*>).map { parseMathKey(it) } })
        }
        val bottom = (m["bottom"] as List<*>).map { parseKey(it, emptyMap()) }
        return MathLayout(tabs, bottom)
    }

    fun symbols(): List<SymbolCategory> {
        val m = read("symbols.yaml") as Map<*, *>
        return (m["categories"] as List<*>).map { c ->
            c as Map<*, *>
            val raw = c["items"]
            val items = when (raw) {
                is String -> raw.trim().split(Regex("\\s+")).map { Output.of(it) }
                is List<*> -> raw.map { item ->
                    when (item) {
                        is Map<*, *> -> Output(item["label"].toString(), unescape(item["text"].toString()))
                        else -> Output.of(item.toString())
                    }
                }
                else -> emptyList()
            }
            SymbolCategory(c["id"].toString(), c["name"].toString(), items, c["wide"] == true)
        }
    }

    /** The default "常用" symbols for Chinese and for Latin/Cyrillic layouts. */
    fun symbolCommons(): Pair<List<Output>, List<Output>> {
        val m = read("symbols.yaml") as Map<*, *>
        fun list(k: String) = (m[k] as? String)?.trim()?.split(Regex("\\s+"))?.map { Output.of(it) } ?: emptyList()
        return list("common_zh") to list("common_latin")
    }

    private fun unescape(s: String) = s.replace("\\u00A0", " ")

    private fun outputs(v: Any?): List<Output> = when (v) {
        is List<*> -> v.map { Output.of(it.toString()) }
        is String -> v.trim().split(Regex("\\s+")).map { Output.of(it) }
        else -> emptyList()
    }

    private fun splitPipes(s: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { sb.append('|'); i += 2; continue }
            if (c == '|') { parts += sb.toString(); sb.clear() } else sb.append(c)
            i++
        }
        parts += sb.toString()
        return parts
    }

    private fun flicksFrom(parts: List<String>): Map<Dir, Output> {
        val dirs = listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT)
        return parts.drop(1).mapIndexedNotNull { idx, p -> if (p.isEmpty() || idx >= 4) null else dirs[idx] to Output.of(p) }.toMap()
    }

    private fun parseKey(node: Any?, extraLong: Map<String, List<Output>>): Key = when (node) {
        is String -> {
            val parts = splitPipes(node)
            val main = parts[0]
            Key(main = Output.of(main), flicks = flicksFrom(parts), long = extraLong[main] ?: emptyList())
        }
        is Map<*, *> -> {
            val w = (node["w"] as? Number)?.toFloat() ?: 1f
            when {
                node.containsKey("spacer") -> Key(spacer = true, width = (node["spacer"] as Number).toFloat())
                node.containsKey("fn") -> Key(
                    fn = node["fn"].toString(), label = node["label"]?.toString(), width = w,
                    long = outputs(node["long"]),
                )
                else -> {
                    val main = node["key"].toString()
                    val fl = mutableMapOf<Dir, Output>()
                    listOf("up" to Dir.UP, "down" to Dir.DOWN, "left" to Dir.LEFT, "right" to Dir.RIGHT).forEach { (k, d) ->
                        node[k]?.let { fl[d] = Output.of(it.toString()) }
                    }
                    Key(
                        main = Output.of(main), flicks = fl,
                        long = outputs(node["long"]) + (extraLong[main] ?: emptyList()),
                        width = w, label = node["label"]?.toString(),
                        style = if (node["style"] == "func") KeyStyle.FUNC else KeyStyle.NORMAL,
                    )
                }
            }
        }
        null -> Key(spacer = true)
        else -> parseKey(node.toString(), extraLong)
    }

    private fun mathOut(v: Any?): Output? {
        val l = v as? List<*> ?: return null
        val label = l[0].toString()
        val uni = l.getOrNull(1)?.toString() ?: label
        val tex = l.getOrNull(2)?.toString() ?: uni
        return Output(label, uni, tex)
    }

    private fun parseMathKey(node: Any?): Key {
        val m = node as Map<*, *>
        val fl = mutableMapOf<Dir, Output>()
        listOf("u" to Dir.UP, "d" to Dir.DOWN, "l" to Dir.LEFT, "r" to Dir.RIGHT).forEach { (k, d) -> mathOut(m[k])?.let { fl[d] = it } }
        return Key(main = mathOut(m["k"]), flicks = fl, width = (m["w"] as? Number)?.toFloat() ?: 1f)
    }
}
