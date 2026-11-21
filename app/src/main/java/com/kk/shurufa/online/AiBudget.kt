package com.kk.shurufa.online

import android.os.SystemClock
import com.kk.shurufa.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How often the keyboard itself may ask an AI service while typing (云联想, AI 候选). Free tiers
 * are small: Gemini 3.5 Flash Lite allowed 15 requests a minute per project and model (measured
 * Nov 2026). The keyboard keeps to [PER_MINUTE] so 改写 and 翻译 still have room, asks once (no
 * retries), and when the service says too many it waits: [SHORT_PAUSE_MS] at first, [LONG_PAUSE_MS]
 * once it keeps happening (a daily quota spent). Requests are counted per day for the settings.
 */
object AiBudget {
    const val PER_MINUTE = 10
    const val SHORT_PAUSE_MS = 40_000L
    const val LONG_PAUSE_MS = 30 * 60_000L

    private val times = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var strikes = 0
    /** Set when a long pause starts, so the keyboard can say so once. */
    @Volatile var justPaused = false

    @Synchronized fun mayAsk(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (now < pausedUntil) return false
        while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
        return times.size < PER_MINUTE
    }

    @Synchronized fun asked(prefs: Prefs, now: Long = SystemClock.elapsedRealtime()) {
        times.addLast(now)
        val day = today()
        val st = prefs.state
        val n = if (st.getString(DAY, "") == day) st.getInt(COUNT, 0) else 0
        st.edit().putString(DAY, day).putInt(COUNT, n + 1).apply()
    }

    @Synchronized fun ok() { strikes = 0 }

    /** The service said too many: wait. True when this starts a long pause. */
    @Synchronized fun limited(now: Long = SystemClock.elapsedRealtime()): Boolean {
        strikes++
        val long = strikes >= 3
        pausedUntil = now + if (long) LONG_PAUSE_MS else SHORT_PAUSE_MS
        if (long) { strikes = 0; justPaused = true }
        return long
    }

    /** Requests made today (by the keyboard while typing). */
    fun today(prefs: Prefs): Int = if (prefs.state.getString(DAY, "") == today()) prefs.state.getInt(COUNT, 0) else 0

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    private const val DAY = "ai_quick_day"
    private const val COUNT = "ai_quick_count"
}
