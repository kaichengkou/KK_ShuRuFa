package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import com.kk.shurufa.Prefs
import com.kk.shurufa.online.Llm

/**
 * AI 候选 (off unless turned on): when a longer stretch of pinyin rests for a moment, the AI
 * service set for 改写 is asked what it means, with the few characters before the caret as
 * context, and its answer joins the bar as the first candidate. This sends what is being typed:
 * it is never asked in passwords, addresses or fields that ask not to be learned from.
 */
class AiCandidate(private val prefs: Prefs, private val onReady: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val results = Handler(Looper.getMainLooper())
    /** The input the answer is for, and the answer. */
    private var forInput = ""
    private var answer = ""
    private var asking = ""
    private var token = 0

    /** The answer for [input], if one came back for exactly that pinyin. */
    fun answerFor(input: String): String? = answer.takeIf { it.isNotEmpty() && forInput == input }

    fun clear() { main.removeCallbacksAndMessages(null); token++; asking = ""; if (forInput.isNotEmpty()) { forInput = ""; answer = "" } }

    /**
     * Called whenever the pinyin changes. [syllables] is how many it has, [first] what Rime puts
     * first (a hint for the service), [before] the text before the caret.
     */
    fun update(input: String, syllables: Int, first: String, before: () -> String, allowed: Boolean) {
        main.removeCallbacksAndMessages(null)
        if (!prefs.aiCandidate || !allowed || syllables < prefs.aiCandSyllables || input.length < 6) { token++; asking = ""; return }
        if (input == forInput || input == asking) return
        val target = Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) ?: return
        // within the keyboard's own budget, and not while the service asked to wait
        if (!com.kk.shurufa.online.AiBudget.mayAsk()) return
        // read from the field only when it is going to be asked (each read is a call into the app)
        val context = before().takeLast(prefs.aiCandContext)
        val t = ++token
        // a moment's rest first: no request for every letter
        main.postDelayed({
            asking = input
            Thread {
                com.kk.shurufa.online.AiBudget.asked(prefs)
                val res = runCatching {
                    Llm.completeQuick(prefs, target, SYSTEM, "Context: $context\nRime's first guess: $first\nPinyin: $input", cancelled = { t != token })
                }
                val e = res.exceptionOrNull()
                if (e is Llm.HttpError && e.code == 429) com.kk.shurufa.online.AiBudget.limited() else if (res.isSuccess) com.kk.shurufa.online.AiBudget.ok()
                val r = res.getOrNull()?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                results.post {
                    if (t != token) return@post
                    asking = ""
                    if (looksRight(r, first)) { forInput = input; answer = r; onReady() }
                }
            }.apply { isDaemon = true }.start()
        }, prefs.aiCandPauseMs.toLong())
    }

    /** Chinese, not pinyin given back, and something Rime did not already put first. */
    private fun looksRight(r: String, first: String): Boolean {
        if (r.isEmpty() || r == first || r.length > 60) return false
        val han = r.count { isCjk(it) }
        val latin = r.count { it in 'a'..'z' || it in 'A'..'Z' }
        return han >= 2 && latin <= han / 4
    }

    companion object {
        private const val SYSTEM =
            "You turn pinyin typed on a phone keyboard into Chinese. The pinyin has no tones; syllables may be " +
                "shortened to initials and may contain a small typo. Using the text already written before it as " +
                "context, and the input method's own first guess as a hint, give the single most likely Simplified " +
                "Chinese for the whole pinyin: natural, as a native speaker would write it, punctuation only where " +
                "clearly meant. Reply with that text only."
    }
}
