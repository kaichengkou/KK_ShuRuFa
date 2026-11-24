package com.kk.shurufa.online

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Where the internet thinks the phone is (IP 属地): the country of the address requests leave
 * from, as Cloudflare sees it. Shown small in the 小K panel, so it is plain at a glance whether
 * an AI service that only serves some regions (Gemini) will answer. One small request when the
 * panel opens, at most every few minutes; nothing typed goes with it.
 */
object IpPlace {
    /** ISO code of the country ("US"), or null until known. */
    @Volatile var country: String? = null; private set
    @Volatile var checking = false; private set
    @Volatile var failed = false; private set
    private var at = 0L
    private val main = Handler(Looper.getMainLooper())
    private const val FRESH_MS = 5 * 60_000L

    /** Regions where Google's Gemini API does not serve requests. */
    private val NO_GEMINI = setOf("CN", "HK", "MO", "RU", "BY", "IR", "KP", "CU", "SY")

    /** Looks again if what is known is old (or [force]); [done] runs on the main thread when it has. */
    fun refresh(force: Boolean = false, done: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (checking || (!force && at > 0 && now - at < FRESH_MS)) return
        checking = true
        Thread {
            val code = runCatching {
                val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
            }.getOrNull()?.lineSequence()?.firstOrNull { it.startsWith("loc=") }?.removePrefix("loc=")?.trim()?.uppercase()
                ?.takeIf { it.length == 2 && it != "XX" }
            main.post {
                checking = false
                at = SystemClock.elapsedRealtime()
                failed = code == null
                if (code != null) country = code
                done()
            }
        }.apply { isDaemon = true; name = "kk-ip" }.start()
    }

    /** "🇯🇵 日本", or null. */
    fun label(): String? {
        val c = country ?: return null
        val flag = String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        val name = Locale("", c).getDisplayCountry(Locale.SIMPLIFIED_CHINESE).ifBlank { c }
        return "$flag $name"
    }

    /** Whether Gemini serves this region (null: not known). */
    fun geminiOk(): Boolean? = country?.let { it !in NO_GEMINI }
}
