package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import com.kk.shurufa.Prefs
import com.kk.shurufa.online.AiBudget
import com.kk.shurufa.online.Llm

/**
 * What 云联想 and AI 候选 share: one request for what is being typed, sent after a pause, counted
 * in the keyboard's AI budget, and dropped when what it was for has changed in the meantime.
 *
 * Asking again for what is already waiting or on its way changes nothing (the bar is synced for
 * many reasons, and each one restarting the pause would hold the request back for good).
 */
class QuickAsk(private val prefs: Prefs) {
    private val waiting = Handler(Looper.getMainLooper())
    /** Answers come on a handler of their own, so dropping what waits never drops an answer. */
    private val answers = Handler(Looper.getMainLooper())
    private var token = 0
    /** The key waiting for its pause, and the one on its way. */
    private var scheduled: String? = null
    var asking: String? = null
        private set

    /** Whether [key] is waiting for its pause or on its way. */
    fun pending(key: String) = key == scheduled || key == asking

    /** What waits for its pause is not sent; a request on its way still answers. */
    fun drop() { waiting.removeCallbacksAndMessages(null); scheduled = null }

    /** Nothing waits and nothing on its way is taken any more. */
    fun cancel() { drop(); token++; asking = null }

    /**
     * Asks for [key] after [pauseMs]: [request] runs off the main thread (blocking, polling its
     * argument to stop early), and [onAnswer] gets its text, or null when it failed, on the main
     * thread, unless something else was asked since.
     */
    fun ask(key: String, pauseMs: Long, request: (cancelled: () -> Boolean) -> String, onAnswer: (String?) -> Unit) {
        if (key == scheduled || key == asking) return
        drop()
        val t = ++token
        scheduled = key
        waiting.postDelayed({
            scheduled = null; asking = key
            Background.ask {
                AiBudget.asked(prefs)
                val r = runCatching { request { t != token } }
                val e = r.exceptionOrNull()
                if (e is Llm.HttpError && e.code == 429) AiBudget.limited() else if (r.isSuccess) AiBudget.ok()
                answers.post {
                    if (t != token) return@post
                    asking = null
                    onAnswer(r.getOrNull())
                }
            }
        }, pauseMs)
    }
}
