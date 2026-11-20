package com.kk.shurufa.online

import android.os.Handler
import android.os.Looper
import com.kk.shurufa.Prefs

/**
 * 翻译 with the engine the user picked: ML Kit on the phone ("mlkit"), or one of the language
 * model services (by provider id). Callbacks arrive on the main thread.
 */
class TranslateService(private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val mlkit by lazy { Translator() }
    @Volatile private var generation = 0

    companion object {
        /** Languages offered in the pickers; "auto" only as a source. */
        val LANGS = listOf(
            "zh" to "中文", "en" to "English", "ru" to "Русский", "ja" to "日本語", "ko" to "한국어",
            "fr" to "Français", "de" to "Deutsch", "es" to "Español",
        )
        private val ENGLISH_NAMES = mapOf("zh" to "Simplified Chinese", "en" to "English", "ru" to "Russian", "ja" to "Japanese",
            "ko" to "Korean", "fr" to "French", "de" to "German", "es" to "Spanish")
        fun name(id: String) = if (id == "auto") "自动" else LANGS.firstOrNull { it.first == id }?.second ?: id

        /** The language of [text], by its script (Latin text counts as English). */
        fun guess(text: String): String = when {
            text.any { it in '぀'..'ヿ' } -> "ja"
            text.any { it in '가'..'힯' } -> "ko"
            text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> "zh"
            text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC } -> "ru"
            else -> "en"
        }
    }

    val engine get() = prefs.translateEngine.takeIf { it == "mlkit" || Llm.configured(prefs, it) } ?: "mlkit"
    fun engineName(e: String = engine) = if (e == "mlkit") "离线" else Llm.provider(e).title

    /** Starts a translation; an older one still running is ignored when it finishes. */
    fun translate(text: String, from: String, to: String, onPartial: (String) -> Unit = {}, done: (Result<String>) -> Unit) {
        val gen = ++generation
        val src = if (from == "auto") guess(text) else from
        if (src == to || text.isBlank()) { done(Result.success(text)); return }
        val e = engine
        if (e == "mlkit") {
            mlkit.translate(text, src, to, prefs.translateWifiOnly) { r -> if (gen == generation) done(r) }
            return
        }
        Thread {
            val r = runCatching {
                Llm.complete(prefs, e,
                    "You are a translation engine inside a phone keyboard. Translate the user's text from ${ENGLISH_NAMES[src] ?: src} " +
                        "into ${ENGLISH_NAMES[to] ?: to}. Reply with the translation only: no quotes, notes or explanations. Keep line breaks, emoji, names and numbers.",
                    text, onText = { t -> if (gen == generation) main.post { if (gen == generation) onPartial(t) } }, cancelled = { gen != generation })
            }
            main.post { if (gen == generation) done(r) }
        }.apply { isDaemon = true }.start()
    }

    fun cancel() { generation++ }
}
