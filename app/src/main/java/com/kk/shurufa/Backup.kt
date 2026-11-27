package com.kk.shurufa

import android.content.Context
import android.content.SharedPreferences
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复: one .zip with what this person has made the keyboard into, to carry to a new phone.
 *
 * In it: the settings and the keyboard's memory (recents, tap positions …), what Rime learned
 * (its own text snapshot of the user dictionary, the format Rime's sync uses), 自定义词库, imported
 * dictionaries, the Russian word memory, typing data and the clipboard's 收藏. Not in it: API keys
 * (secrets stay on the phone they were typed on), the clipboard's history, downloaded models.
 *
 * Restoring writes all of it back; Rime merges the snapshot into what it already knows at its next
 * start, so nothing learned on the new phone is lost either.
 */
object Backup {
    private const val FORMAT = 1

    class Summary(val words: Int, val phrases: Int, val dicts: Int, val settings: Int)

    // ---- export ----

    /** Writes the backup to [out]. Blocking (asks Rime for a fresh snapshot first): call it off the main thread. */
    fun export(ctx: Context, out: OutputStream): Summary {
        val user = RimeEngine.userDir(ctx)
        // what Rime learned, as text: its sync writes <user>/sync/<installation>/*.userdb.txt
        if (RimeEngine.ready) runCatching { RimeBridge.syncUserData() }
        val snapshots = ownSnapshots(user)
        val prefs = Prefs(ctx)
        var words = 0
        var phrases = 0
        val dicts = user.listFiles()?.filter { it.name.startsWith("kk_import") && it.name.endsWith(".dict.yaml") }.orEmpty()
        val settings = toJson(prefs.sp) { !isSecret(it) }
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("manifest.json", JSONObject().put("app", "KK输入法").put("format", FORMAT)
                .put("version", BuildConfig.VERSION_NAME).put("created", System.currentTimeMillis()).toString(2).toByteArray())
            put("prefs.json", settings.toString(1).toByteArray())
            put("state.json", toJson(prefs.state) { true }.toString(1).toByteArray())
            for (f in snapshots) {
                put("rime/userdb/${f.name}", f.readBytes())
                words += f.useLines { l -> l.count { it.isNotBlank() && !it.startsWith('#') } }
            }
            RimeEngine.customPhraseFile(ctx).takeIf { it.isFile }?.let { f ->
                put("rime/custom_phrase.txt", f.readBytes())
                phrases = f.useLines { l -> l.count { it.isNotBlank() && !it.startsWith('#') } }
            }
            for (f in dicts) put("rime/${f.name}", f.readBytes())
            com.kk.shurufa.ime.Muted.file(ctx).takeIf { it.isFile }?.let { put("rime/${com.kk.shurufa.ime.Muted.FILE}", it.readBytes()) }
            for (f in ctx.filesDir.listFiles().orEmpty()) if (f.name.startsWith("learned_") && f.name.endsWith(".tsv")) put("files/${f.name}", f.readBytes())
            File(ctx.filesDir, "typing_stats.json").takeIf { it.isFile }?.let { put("files/typing_stats.json", it.readBytes()) }
            // the clipboard's 收藏, not its history (codes and passwords pass through it)
            val fav = File(ctx.filesDir, "clipboard.txt").takeIf { it.isFile }?.readText()?.split('\u0002')
                ?.filter { it.length > 1 && it[0] == '1' && !it.startsWith("1" + IMAGE_MARK) }.orEmpty()
            if (fav.isNotEmpty()) put("files/clip_favorites.txt", fav.joinToString("\u0002").toByteArray())
        }
        return Summary(words, phrases, dicts.size, settings.length())
    }

    // ---- restore ----

    /**
     * Restores a backup read from [input]. Settings and files are written at once; what Rime
     * learned is merged at its next start (the caller restarts the keyboard).
     */
    fun restore(ctx: Context, input: InputStream): Summary {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && !e.name.contains("..")) entries[e.name] = z.readBytes()
            }
        }
        val manifest = entries["manifest.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
        if (manifest == null || manifest.optString("app") != "KK输入法") error("这不是 KK输入法 的备份文件")
        val prefs = Prefs(ctx)
        val user = RimeEngine.userDir(ctx).apply { mkdirs() }
        var settings = 0
        entries["prefs.json"]?.let { b -> settings = fromJson(prefs.sp, JSONObject(String(b))) { !isSecret(it) } }
        entries["state.json"]?.let { b -> fromJson(prefs.state, JSONObject(String(b))) { true } }
        // Rime merges every snapshot in its sync folder into its own dictionary
        val restore = File(user, "sync/$RESTORE_DIR").apply { deleteRecursively(); mkdirs() }
        var words = 0
        var phrases = 0
        var dicts = 0
        for ((name, b) in entries) {
            when {
                name.startsWith("rime/userdb/") && name.endsWith(".userdb.txt") -> {
                    File(restore, name.substringAfterLast('/')).writeBytes(b)
                    words += String(b).lineSequence().count { it.isNotBlank() && !it.startsWith('#') }
                }
                name == "rime/custom_phrase.txt" -> {
                    phrases = mergeLines(RimeEngine.customPhraseFile(ctx), String(b))
                }
                name.startsWith("rime/kk_import") && name.endsWith(".dict.yaml") -> {
                    File(user, name.substringAfter('/')).writeBytes(b); dicts++
                }
                name.startsWith("files/learned_") && name.endsWith(".tsv") -> File(ctx.filesDir, name.substringAfter('/')).writeBytes(b)
                name == "rime/${com.kk.shurufa.ime.Muted.FILE}" -> com.kk.shurufa.ime.Muted.merge(ctx, String(b))
                name == "files/typing_stats.json" -> File(ctx.filesDir, "typing_stats.json").writeBytes(b)
                name == "files/clip_favorites.txt" -> mergeFavorites(File(ctx.filesDir, "clipboard.txt"), String(b))
            }
        }
        if (restore.listFiles().isNullOrEmpty()) restore.delete()
        // the dictionaries are rebuilt with the restored imports and phrases at the next start
        File(user, REDEPLOY_MARK).writeText("restore")
        return Summary(words, phrases, dicts, settings)
    }

    // ---- what the engine does with it ----

    /** Folder under <user>/sync that a restore puts its snapshots in. */
    const val RESTORE_DIR = "kk_restore"
    /** A file in the user folder that asks the next start for a full deploy. */
    const val REDEPLOY_MARK = ".kk_redeploy"

    /** The snapshots this phone's Rime wrote (not one a restore put there). */
    private fun ownSnapshots(user: File): List<File> {
        val id = File(user, "installation.yaml").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("installation_id:") }?.substringAfter(':')?.trim()?.trim('"')
        val dirs = File(user, "sync").listFiles()?.filter { it.isDirectory && it.name != RESTORE_DIR }.orEmpty()
        val mine = dirs.firstOrNull { it.name == id } ?: dirs.maxByOrNull { it.lastModified() }
        return mine?.listFiles()?.filter { it.name.endsWith(".userdb.txt") }.orEmpty()
    }

    // ---- helpers ----

    private const val IMAGE_MARK = "\u0001img:"

    /**
     * 导出设置: the settings alone (no learned words, no API services at all), as the JSON a
     * preset is made of. Returns how many were written.
     */
    fun exportSettings(ctx: Context, out: OutputStream): Int {
        val o = toJson(Prefs(ctx).sp) { !isService(it) }
        out.write(o.toString(1).toByteArray())
        return o.length()
    }

    /**
     * 预设: on a phone the keyboard has never run on, the settings in assets/preset_prefs.json
     * (made with [exportSettings]) are where it starts. Once only; nothing is changed after.
     */
    fun applyPreset(ctx: Context, sp: SharedPreferences, state: SharedPreferences) {
        if (state.getBoolean(PRESET_DONE, false)) return
        // unit tests check the built-in defaults, not this phone's start
        if (android.os.Build.FINGERPRINT == "robolectric") return
        state.edit().putBoolean(PRESET_DONE, true).apply()
        if (sp.all.isNotEmpty()) return
        val text = runCatching { ctx.assets.open("preset_prefs.json").bufferedReader().readText() }.getOrNull() ?: return
        runCatching { fromJson(sp, JSONObject(text)) { !isService(it) } }
    }
    private const val PRESET_DONE = "preset_done"

    /** Everything about AI services: keys, addresses, models, which one is used. */
    private fun isService(key: String) = isSecret(key) || key.startsWith("llm_") || key in setOf(Prefs.AI_PROVIDER, Prefs.QUICK_TARGET, Prefs.TRANSLATE_ENGINE)

    /** API keys never leave the phone in a backup. */
    private fun isSecret(key: String) = key == Prefs.AI_KEY || (key.startsWith("llm_") && key.endsWith("_key"))

    private fun toJson(sp: SharedPreferences, keep: (String) -> Boolean): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (!keep(k) || v == null) continue
            val typed = JSONObject()
            when (v) {
                is Boolean -> typed.put("b", v)
                is Int -> typed.put("i", v)
                is Long -> typed.put("l", v)
                is Float -> typed.put("f", v.toDouble())
                is String -> typed.put("s", v)
                is Set<*> -> typed.put("ss", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, typed)
        }
        return o
    }

    /** Writes [o] into [sp] with each value's own type. Returns how many were set. */
    private fun fromJson(sp: SharedPreferences, o: JSONObject, keep: (String) -> Boolean): Int {
        val ed = sp.edit()
        var n = 0
        for (k in o.keys()) {
            if (!keep(k)) continue
            val t = o.optJSONObject(k) ?: continue
            when {
                t.has("b") -> ed.putBoolean(k, t.getBoolean("b"))
                t.has("i") -> ed.putInt(k, t.getInt("i"))
                t.has("l") -> ed.putLong(k, t.getLong("l"))
                t.has("f") -> ed.putFloat(k, t.getDouble("f").toFloat())
                t.has("s") -> ed.putString(k, t.getString("s"))
                t.has("ss") -> t.getJSONArray("ss").let { a -> ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }

    /** Adds the lines of [text] that [f] does not have yet. Returns the lines added. */
    private fun mergeLines(f: File, text: String): Int {
        val have = if (f.isFile) f.readLines().toHashSet() else HashSet()
        val add = text.lines().filter { it.isNotBlank() && !it.startsWith('#') && it !in have }
        if (add.isEmpty()) return 0
        f.parentFile?.mkdirs()
        val sep = if (f.isFile && f.length() > 0 && !f.readText().endsWith("\n")) "\n" else ""
        f.appendText(sep + add.joinToString("\n") + "\n")
        return add.size
    }

    /** Adds the restored 收藏 to the clipboard file, pinned, without doubles. */
    private fun mergeFavorites(f: File, fav: String) {
        val have = if (f.isFile) f.readText().split('\u0002').filter { it.length > 1 } else emptyList()
        val texts = have.map { it.substring(1) }.toHashSet()
        val add = fav.split('\u0002').filter { it.length > 1 && it.substring(1) !in texts }
        if (add.isNotEmpty()) f.writeText((add + have).joinToString("\u0002"))
    }
}
