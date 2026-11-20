package com.kk.shurufa.online

import android.content.Context
import com.kk.shurufa.rime.RimeEngine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 词库更新: fetches the newest rime-ice dictionaries (the ones this keyboard uses) through
 * jsDelivr, writes the changed ones into Rime's user folder, where they take precedence over
 * the copies shipped in the APK, and redeploys.
 */
object DictUpdater {
    private const val LIST = "https://data.jsdelivr.com/v1/packages/gh/iDvel/rime-ice@main?structure=flat"
    private val BASES = listOf("https://cdn.jsdelivr.net/gh/iDvel/rime-ice@main", "https://fastly.jsdelivr.net/gh/iDvel/rime-ice@main")
    val FILES = listOf(
        "/cn_dicts/8105.dict.yaml", "/cn_dicts/base.dict.yaml", "/cn_dicts/ext.dict.yaml",
        "/cn_dicts/tencent.dict.yaml", "/cn_dicts/others.dict.yaml",
        "/en_dicts/en.dict.yaml", "/en_dicts/en_ext.dict.yaml",
    )

    class Plan(val changed: List<Pair<String, Long>>) { val bytes get() = changed.sumOf { it.second } }

    private fun stateFile(ctx: Context) = File(RimeEngine.userDir(ctx), ".kk_dict_hashes")
    private fun state(ctx: Context): MutableMap<String, String> =
        stateFile(ctx).takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()?.toMutableMap() ?: mutableMapOf()

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "KK-IME")
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); error("HTTP $code") }
        return c
    }

    /** What would be downloaded: files whose hash differs from the last update. Blocking. */
    fun check(ctx: Context): Plan {
        val files = JSONObject(get(LIST).inputStream.bufferedReader().use { it.readText() }).getJSONArray("files")
        val known = state(ctx)
        val changed = ArrayList<Pair<String, Long>>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.getString("name")
            if (name in FILES && known[name] != f.getString("hash")) changed += name to f.getLong("size")
        }
        return Plan(changed)
    }

    /** Downloads [plan] and redeploys Rime. Blocking. [progress] gets 0..1. */
    fun apply(ctx: Context, plan: Plan, progress: (Float) -> Unit) {
        val files = JSONObject(get(LIST).inputStream.bufferedReader().use { it.readText() }).getJSONArray("files")
        val hashes = (0 until files.length()).associate { files.getJSONObject(it).let { f -> f.getString("name") to f.getString("hash") } }
        val known = state(ctx)
        val total = plan.bytes.coerceAtLeast(1)
        var done = 0L
        val user = RimeEngine.userDir(ctx)
        for ((name, _) in plan.changed) {
            val out = File(user, name.trimStart('/'))
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            var ok = false
            var last: Exception? = null
            for (base in BASES) {
                try {
                    get(base + name).inputStream.use { input ->
                        tmp.outputStream().use { o ->
                            val buf = ByteArray(128 * 1024)
                            while (true) { val n = input.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(done.toFloat() / total) }
                        }
                    }
                    ok = true; break
                } catch (e: Exception) { last = e }
            }
            if (!ok) throw last ?: IllegalStateException("下载失败")
            tmp.renameTo(out)
            hashes[name]?.let { known[name] = it }
        }
        stateFile(ctx).writeText(known.entries.joinToString("\n") { it.key + "\t" + it.value })
        RimeEngine.redeploy(ctx)
    }
}
