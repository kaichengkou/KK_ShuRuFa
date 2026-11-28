package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import com.kk.shurufa.Prefs
import com.kk.shurufa.online.AiBudget
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
 *
 * Waiting kept under a second (Nov 2026): the pause is short (300 ms), and none at all after the
 * end of a sentence; while pinyin waits on the bar, the text it would make with its first
 * candidate is asked ahead ([prefetch]), so the continuations are there or on their way when
 * space takes it; text that goes on the way a continuation said gets the rest of it at once,
 * without asking; and the connection to the service is opened when the keyboard opens ([warm]).
 */
class CloudPredict(private val prefs: Prefs, private val onReady: () -> Unit) {
    private val ask = QuickAsk(prefs)
    private val main = Handler(Looper.getMainLooper())
    private var ahead: Runnable? = null
    private var forText = ""
    private var answer: List<String> = emptyList()
    /** Recent answers, so going back to the same text needs no new request. */
    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 24
    }

    /** The continuations for exactly [before] (the text before the caret), if they came. */
    fun answerFor(before: String): List<String> = if (before.isNotEmpty() && keyOf(before) == forText) answer else emptyList()

    /**
     * What is sent: the last few dozen characters before the caret, from the start of the line
     * (a new line in a chat is a new message, often in another language: the one before would
     * only lead the continuations astray).
     */
    private fun keyOf(before: String) = before.takeLast(prefs.cloudContext).substringAfterLast('\n')

    fun clear() { ask.cancel(); ahead?.let(main::removeCallbacks) }

    /** Called when suggestions show for [before]; asks after a short pause if this text is new. */
    fun update(before: String, allowed: Boolean) {
        ahead?.let(main::removeCallbacks)
        val key = keyOf(before)
        if (!prefs.cloudPredict || !allowed || key.isBlank() || key.trim().length < 2) { ask.cancel(); return }
        if (key == forText) { ask.drop(); return }
        // asked ahead for this very text (or already waiting): sent now if it still waits
        if (ask.pending(key)) { ask.hurry(key); return }
        cache[key]?.let { forText = key; answer = it; ask.drop(); return }
        // the text went on the way a continuation said: the rest of it, without asking
        continued(key)?.let { forText = key; answer = it; ask.drop(); return }
        send(key, if (sentenceEnd(key) && !prefs.powerSave()) 0L else prefs.cloudPauseNowMs) ?: ask.drop()
    }

    /**
     * While pinyin waits on the bar: asks ahead for the text that taking its first candidate
     * [first] would make, after a short pause. Not on mobile data or in 省电模式, and only while
     * the minute's budget has room to spare (what is asked ahead may not be taken).
     */
    fun prefetch(first: String, before: () -> String, allowed: Boolean) {
        ahead?.let(main::removeCallbacks)
        if (!prefs.cloudPredict || !allowed || first.isEmpty()) return
        val r = Runnable {
            if (prefs.metered() || prefs.powerSave() || AiBudget.room() < AHEAD_ROOM) return@Runnable
            val key = keyOf(before() + first)
            if (key.isBlank() || key.trim().length < 2 || key == forText || ask.pending(key) || cache.containsKey(key)) return@Runnable
            send(key, 0L)
        }
        ahead = r
        main.postDelayed(r, AHEAD_PAUSE_MS)
    }

    /** Asks for [key] after [pauseMs]; null when it may not be asked now. */
    private fun send(key: String, pauseMs: Long): Unit? {
        val target = Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) ?: return null
        // within the keyboard's own budget, and not while the service asked to wait
        if (!AiBudget.mayAsk() || !prefs.quickAiNetworkOk()) return null
        val count = prefs.cloudCount
        ask.ask(key, pauseMs, { cancelled -> Llm.completeQuick(prefs, target, system(count), key, cancelled) }) { raw ->
            val list = parse(raw.orEmpty(), key, count)
            // a request that failed (no answer at all) is asked again next time; an answer is kept
            if (raw != null) cache[key] = list
            if (list.isNotEmpty()) { forText = key; answer = list; onReady() }
        }
        return Unit
    }

    private fun continued(key: String) = continuation(cache.entries.map { it.key to it.value }.asReversed(), key)

    /** Opens the connection to the service ahead of the first request (when the keyboard opens). */
    fun warm() {
        if (!prefs.cloudPredict || prefs.metered() || prefs.powerSave()) return
        val target = Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) ?: return
        Background.ask { Llm.warm(prefs, target) }
    }

    companion object {
        /** How long pinyin waits on the bar before what it would make is asked ahead. */
        const val AHEAD_PAUSE_MS = 350L
        /** Requests the minute must still allow for one to be asked ahead (AI 候选 and 改写 need some). */
        const val AHEAD_ROOM = 4

        /**
         * What is left of a continuation given before (in [given], text and its continuations,
         * the latest first), when the text typed since is how that continuation began.
         */
        fun continuation(given: List<Pair<String, List<String>>>, key: String): List<String>? {
            for ((k, list) in given) {
                if (k.length >= key.length || !key.startsWith(k)) continue
                val typed = key.substring(k.length).trimStart()
                if (typed.isEmpty()) continue
                val rest = list.filter { it.length > typed.length && it.startsWith(typed) }
                    .map { it.substring(typed.length).trim() }.filter { it.isNotEmpty() }.distinct()
                if (rest.isNotEmpty()) return rest
            }
            return null
        }

        /** The text ends a sentence or a clause: a natural pause, so it is asked at once. */
        fun sentenceEnd(text: String): Boolean = text.trimEnd().lastOrNull()?.let { it in "，。！？；：…,.!?;:" } == true

        private fun system(n: Int) =
            "You are the next-phrase suggester of a phone keyboard. Given the text the user has typed so far " +
                "(it may end mid-sentence), suggest $n different short continuations they are likely to type next: " +
                "each 1 to 6 words (Chinese: 2 to 8 characters), natural for a chat message, in the same language " +
                "as the end of the text, written as the user (first person; the user is a man, so gendered forms such as Russian past tense are masculine). Do not repeat the " +
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
