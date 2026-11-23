package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import com.kk.shurufa.Prefs
import com.kk.shurufa.online.Llm

/**
 * 云联想 (off unless turned on): when typing pauses where suggestions show (after a Chinese word
 * is committed, after a space in Russian or English, after a suggestion is picked), the AI service
 * set for 改写 is asked how the text goes on, and up to three short continuations join the front
 * of the suggestions, marked 云. It sees the last few dozen characters (a setting) before the caret and
 * nothing else; it is never asked in passwords, addresses, fields that ask not to be learned
 * from, or in 临时模式.
 *
 * Compared before choosing (Nov 2026): keyless cloud pinyin (Google Input Tools) gave what 雾凇
 * already gives; search suggestions are fast but read like searches (…歌词, …怎么回复) and miss
 * chat; the AI service gave three chat-like continuations in 0.5–0.75 s with Gemini 3.5 Flash
 * Lite, in Chinese, Russian and English alike.
 */
class CloudPredict(private val prefs: Prefs, private val onReady: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val results = Handler(Looper.getMainLooper())
    private var forText = ""
    private var answer: List<String> = emptyList()
    private var asking = ""
    private var token = 0
    /** Recent answers, so going back to the same text needs no new request. */
    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 24
    }

    /** The continuations for exactly [before] (the text before the caret), if they came. */
    fun answerFor(before: String): List<String> = if (before.isNotEmpty() && before.takeLast(prefs.cloudContext) == forText) answer else emptyList()

    fun clear() { main.removeCallbacksAndMessages(null); token++; asking = "" }

    /** Called when suggestions show for [before]; asks after a short pause if this text is new. */
    fun update(before: String, allowed: Boolean) {
        main.removeCallbacksAndMessages(null)
        val key = before.takeLast(prefs.cloudContext)
        if (!prefs.cloudPredict || !allowed || key.isBlank() || key.trim().length < 2) { token++; asking = ""; return }
        if (key == forText || key == asking) return
        cache[key]?.let { forText = key; answer = it; return }
        val target = Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) ?: return
        // within the keyboard's own budget, and not while the service asked to wait
        if (!com.kk.shurufa.online.AiBudget.mayAsk() || !prefs.quickAiNetworkOk()) return
        val t = ++token
        main.postDelayed({
            asking = key
            Thread {
                com.kk.shurufa.online.AiBudget.asked(prefs)
                val r = runCatching { Llm.completeQuick(prefs, target, system(prefs.cloudCount), key, cancelled = { t != token }) }
                val e = r.exceptionOrNull()
                if (e is Llm.HttpError && e.code == 429) com.kk.shurufa.online.AiBudget.limited() else if (r.isSuccess) com.kk.shurufa.online.AiBudget.ok()
                val raw = r.getOrNull().orEmpty()
                val list = parse(raw, key, prefs.cloudCount)
                results.post {
                    if (t != token) return@post
                    asking = ""
                    cache[key] = list
                    if (list.isNotEmpty()) { forText = key; answer = list; onReady() }
                }
            }.apply { isDaemon = true; name = "kk-cloud" }.start()
        }, prefs.cloudPauseMs.toLong())
    }

    companion object {
        private fun system(n: Int) =
            "You are the next-phrase suggester of a phone keyboard. Given the text the user has typed so far " +
                "(it may end mid-sentence), suggest $n different short continuations they are likely to type next: " +
                "each 1 to 6 words (Chinese: 2 to 8 characters), natural for a chat message, in the same language " +
                "as the end of the text, written as the user (first person; the user is a man). Do not repeat the " +
                "text. One per line, nothing else."

        /** Up to three clean lines: no numbering, quotes or repeats of the text, of a sensible length. */
        fun parse(raw: String, before: String, max: Int = 3): List<String> {
            val tail = before.trimEnd().takeLast(12)
            return raw.lines().map { l ->
                l.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.、)]\\s*"), "")
                    .trim('"', '“', '”', '「', '」', '\'', ' ')
            }.filter { s ->
                s.isNotEmpty() && s.length <= 40 && !tail.endsWith(s) && s != before.trim()
            }.distinct().take(max)
        }
    }
}
