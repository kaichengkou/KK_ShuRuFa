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

/** NORMAL solid (letters, digits), FUNC/SOFT translucent ink (functions, symbols), ACTION accent (enter), TINT translucent accent (a way to another panel). */
enum class KeyStyle { NORMAL, FUNC, SOFT, ACTION, TINT }

private fun defaultStyle(fn: String?) = when (fn) {
    null, "space" -> KeyStyle.NORMAL
    "enter" -> KeyStyle.ACTION
    else -> KeyStyle.FUNC
}

/** A user edit of one key from the key editor: flick characters and long-press list. */
data class KeyOverride(val up: String?, val down: String?, val left: String?, val right: String?, val long: List<String>?)

class Key(
    val main: Output? = null,
    val flicks: Map<Dir, Output> = emptyMap(),
    val long: List<Output> = emptyList(),
    val fn: String? = null,
    val label: String? = null,
    val width: Float = 1f,
    val style: KeyStyle = defaultStyle(fn),
    val spacer: Boolean = false,
) {
    val isLetter get() = main != null && main.text.length == 1 && main.text[0].isLetter()

    fun copy(flicks: Map<Dir, Output> = this.flicks, long: List<Output> = this.long, style: KeyStyle = this.style) =
        Key(main, flicks, long, fn, label, width, style, spacer)

    fun withOverride(o: KeyOverride): Key {
        val fl = flicks.toMutableMap()
        listOf(Dir.UP to o.up, Dir.DOWN to o.down, Dir.LEFT to o.left, Dir.RIGHT to o.right).forEach { (d, v) ->
            if (v != null) { if (v.isEmpty()) fl.remove(d) else fl[d] = Output.of(v) }
        }
        return copy(flicks = fl, long = o.long?.map { Output.of(it) } ?: long)
    }
}

class KeyboardLayout(
    val id: String,
    val label: String,
    val engine: String,
    val rows: List<List<Key>>,
)

class MathTab(val id: String, val name: String, val rows: List<List<Key>>)
/** 学术键盘: [pad] is the fixed number block on the right, each tab fills the 4×4 block on the left. */
class MathLayout(val tabs: List<MathTab>, val pad: List<List<Key>>)

class SymbolCategory(val id: String, val name: String, val items: List<Output>, val wide: Boolean)

/**
 * Loads keyboard layouts from YAML. Each file in assets/layouts can be overridden by a file with
 * the same name in the app's external files folder (Android/data/com.kk.shurufa/files/layouts).
 */
class LayoutRepo(
    private val context: Context?,
    private val source: ((String) -> String)? = null,
    /** Per-layout key edits keyed by the key's main character. */
    private val overrides: ((String) -> Map<String, KeyOverride>)? = null,
) {
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
        val ov = overrides?.invoke(id).orEmpty()
        val rows = (m["rows"] as List<*>).map { row ->
            (row as List<*>).map { parseKey(it, extraLong) }.map { k -> k.main?.text?.let { ov[it] }?.let { k.withOverride(it) } ?: k }
        }
        return KeyboardLayout(id, m["label"]?.toString() ?: id, m["engine"]?.toString() ?: "direct", rows)
    }

    fun math(): MathLayout {
        val m = read("math.yaml") as Map<*, *>
        val tabs = (m["tabs"] as List<*>).map { t ->
            t as Map<*, *>
            MathTab(t["id"].toString(), t["name"].toString(),
                (t["keys"] as List<*>).map { r -> (r as List<*>).map { parseMathKey(it).copy(style = KeyStyle.SOFT) } })
        }
        // digits are solid like on every number pad; operators are soft
        val pad = (m["pad"] as List<*>).map { r ->
            (r as List<*>).map { parseMathKey(it) }.map { k ->
                if (k.main?.text?.singleOrNull()?.isDigit() == true) k else k.copy(style = KeyStyle.SOFT)
            }
        }
        return MathLayout(tabs, pad)
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
                    style = styleOf(node["style"]) ?: defaultStyle(node["fn"].toString()),
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
                        style = styleOf(node["style"]) ?: KeyStyle.NORMAL,
                    )
                }
            }
        }
        null -> Key(spacer = true)
        else -> parseKey(node.toString(), extraLong)
    }

    private fun styleOf(v: Any?) = when (v?.toString()) {
        "func" -> KeyStyle.FUNC
        "soft" -> KeyStyle.SOFT
        "action" -> KeyStyle.ACTION
        "normal" -> KeyStyle.NORMAL
        "tint" -> KeyStyle.TINT
        else -> null
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
