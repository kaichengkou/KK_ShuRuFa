package com.kk.shurufa.ime

import com.kk.shurufa.Prefs
import com.kk.shurufa.online.AiBudget
import com.kk.shurufa.online.Llm

/**
 * AI 候选 (off unless turned on): when a longer stretch of pinyin rests for a moment, the AI
 * service set for 改写 is asked what it means, with the few characters before the caret as
 * context, and its answer joins the bar as the first candidate. This sends what is being typed:
 * it is never asked in passwords, addresses or fields that ask not to be learned from.
 */
class AiCandidate(private val prefs: Prefs, private val onReady: () -> Unit) {
    private val ask = QuickAsk(prefs)
    /** The input the answer is for, and the answer. */
    private var forInput = ""
    private var answer = ""

    /** The answer for [input], if one came back for exactly that pinyin. */
    fun answerFor(input: String): String? = answer.takeIf { it.isNotEmpty() && forInput == input }

    fun clear() { ask.cancel(); if (forInput.isNotEmpty()) { forInput = ""; answer = "" } }

    /**
     * Called whenever the pinyin changes. [syllables] is how many it has, [first] what Rime puts
     * first (a hint for the service), [before] the text before the caret.
     */
    fun update(input: String, syllables: Int, first: String, before: () -> String, allowed: Boolean) {
        if (!prefs.aiCandidate || !allowed || syllables < prefs.aiCandSyllables || input.length < 6) { ask.cancel(); return }
        if (input == forInput) { ask.drop(); return }
        // already waiting for its pause or on its way: the pause is not started again
        if (ask.pending(input)) return
        val target = Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) ?: return ask.drop()
        // within the keyboard's own budget, and not while the service asked to wait
        if (!AiBudget.mayAsk() || !prefs.quickAiNetworkOk()) return ask.drop()
        // read from the field only when it is going to be asked (each read is a call into the app)
        val context = before().takeLast(prefs.aiCandContext)
        // a moment's rest first: no request for every letter
        ask.ask(input, prefs.aiCandPauseMs.toLong(), { cancelled ->
            Llm.completeQuick(prefs, target, SYSTEM, "Context: $context\nRime's first guess: $first\nPinyin: $input", cancelled)
        }) { res ->
            val r = res?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (looksRight(r, first)) { forInput = input; answer = r; onReady() }
        }
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
