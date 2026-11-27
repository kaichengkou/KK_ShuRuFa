package com.kk.shurufa.online

import android.content.Context
import com.kk.shurufa.BuildConfig
import com.kk.shurufa.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新: the newest release of KK输入法 on GitHub (its tag is the version, "v0.18.8"), against
 * the version running. One small request to GitHub, nothing about the phone or what is typed
 * goes with it. Checked when asked, and at most once a day when the settings open.
 */
object Updates {
    private const val API = "https://api.github.com/repos/kaichengkou/KK_ShuRuFa/releases/latest"
    const val PAGE = "https://github.com/kaichengkou/KK_ShuRuFa/releases/latest"

    class Release(val version: String, val page: String, val apk: String?, val notes: String) {
        val newer get() = isNewer(version, BuildConfig.VERSION_NAME)
    }

    /** Blocking: the latest release, or an error saying why not. */
    fun latest(): Result<Release> = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val code = c.responseCode
        if (code == 404) error("还没有发布过版本")
        if (code !in 200..299) error("GitHub 暂时连不上（$code）")
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val apk = o.optJSONArray("assets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") }
        Release(o.optString("tag_name").removePrefix("v"), o.optString("html_url", PAGE), apk, o.optString("body").trim())
    }

    /** "0.18.10" after "0.18.9": number by number. */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    /** Once a day at most: is a newer version out? (remembered, for the settings to say so) */
    fun checkDaily(ctx: Context, done: (Release?) -> Unit) {
        val st = Prefs(ctx).state
        val today = java.time.LocalDate.now().toString()
        if (st.getString(CHECKED, "") == today) { done(cached(ctx)); return }
        Thread {
            val r = latest().getOrNull()
            st.edit().putString(CHECKED, today).apply { if (r != null) putString(SEEN, "${r.version}\t${r.page}\t${r.apk.orEmpty()}") }.apply()
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(r?.takeIf { it.newer }) }
        }.apply { isDaemon = true }.start()
    }

    fun cached(ctx: Context): Release? {
        val p = (Prefs(ctx).state.getString(SEEN, "") ?: "").split('\t')
        if (p.size < 2 || p[0].isEmpty()) return null
        return Release(p[0], p[1], p.getOrNull(2)?.ifEmpty { null }, "").takeIf { it.newer }
    }

    private const val CHECKED = "update_checked"
    private const val SEEN = "update_seen"
}
