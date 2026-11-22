package com.kk.shurufa.ime

import android.content.Context
import com.kk.shurufa.Prefs
import com.kk.shurufa.online.Llm
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 小K: the small life that lives in the keyboard. It was born the day the keyboard was first used,
 * feels each key, sleeps while nobody types, says one line the first time the keyboard opens on a
 * new day, and talks when asked (接着聊, the 小K key). Nothing of what it is told is kept beyond
 * the conversation.
 *
 * The rules that keep it from being a nuisance: one line a day, never a question, gone at the
 * first touch; never in passwords, addresses or 临时模式; nothing typed is ever sent anywhere
 * unless the person is talking to it. With an AI service it writes its own line (one request a
 * day, asked the evening before) and talks; without one it has its own lines and its face.
 */
class Kay(private val context: Context, private val prefs: Prefs) {
    val name get() = prefs.kayName

    init {
        // 0.16.0 kept notes of conversations; it no longer does
        File(context.filesDir, "kay_memory.txt").delete()
    }

    // ---- age ----

    /** The day 小K was born: the keyboard's first day. */
    val born: LocalDate get() {
        val s = prefs.state.getString(Prefs.KAY_BORN, null)
        if (s != null) return runCatching { LocalDate.parse(s) }.getOrDefault(LocalDate.now())
        val today = LocalDate.now()
        prefs.state.edit().putString(Prefs.KAY_BORN, today.toString()).apply()
        return today
    }

    /** Days alive, counting the first as day 1. */
    fun age(today: LocalDate = LocalDate.now()) = ChronoUnit.DAYS.between(born, today).toInt() + 1

    // ---- the line of the day ----

    /** Whether the line of the day is still to be said ([field] is a plain, empty text field). */
    fun greetingDue(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean =
        prefs.kayGreet && now.hour >= 5 && prefs.state.getString(Prefs.KAY_GREETED, "") != today.toString()

    fun markGreeted(today: LocalDate = LocalDate.now()) = prefs.state.edit().putString(Prefs.KAY_GREETED, today.toString()).apply()

    /** Today's line: the one the AI wrote for today, or else one of 小K's own. */
    fun line(today: LocalDate = LocalDate.now(), now: LocalTime = LocalTime.now()): String {
        val st = prefs.state
        if (prefs.kayAiGreet && st.getString(Prefs.KAY_LINE_FOR, "") == today.toString()) st.getString(Prefs.KAY_LINE, null)?.let { return it }
        return KayLines.local(today, now, age(today), yesterdayChars(today))
    }

    private fun yesterdayChars(today: LocalDate): Int =
        TypingStats.get(context).snapshot().days[today.minusDays(1).toString()]?.total ?: 0

    /**
     * In the evening, tomorrow's line is asked for once (so the morning needs no waiting), with
     * what is known of tomorrow and how today went. Nothing typed is sent.
     */
    fun prefetch(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()) {
        if (!prefs.kayAiGreet || now.hour < 18) return
        val tomorrow = today.plusDays(1)
        val st = prefs.state
        if (st.getString(Prefs.KAY_LINE_FOR, "") == tomorrow.toString() || st.getString(Prefs.KAY_ASKED, "") == today.toString()) return
        val target = Llm.resolve(prefs, prefs.quickTarget.ifEmpty { prefs.aiProvider }) ?: return
        if (!com.kk.shurufa.online.AiBudget.mayAsk()) return
        st.edit().putString(Prefs.KAY_ASKED, today.toString()).apply()
        val facts = KayLines.facts(tomorrow, age(tomorrow), TypingStats.get(context).snapshot().days[today.toString()]?.total ?: 0)
        val recent = listOfNotNull(st.getString(Prefs.KAY_LINE, null))
        Background.download({
            com.kk.shurufa.online.AiBudget.asked(prefs)
            Llm.completeQuick(prefs, target, KayLines.greetSystem(name), KayLines.greetUser(facts, recent))
        }) { raw ->
            val line = KayLines.cleanLine(raw) ?: return@download
            st.edit().putString(Prefs.KAY_LINE_FOR, tomorrow.toString()).putString(Prefs.KAY_LINE, line).apply()
        }
    }

    // ---- talking ----

    class Turn(val mine: Boolean, val text: String)
    /** This conversation, while the keyboard lives; the oldest turns fall away. */
    val talk = ArrayList<Turn>()

    /** How many times 小K was talked to today, against the day's cap. */
    fun mayTalk(): Boolean {
        val (day, n) = talkCount()
        return day != LocalDate.now().toString() || n < prefs.kayChatCap
    }

    fun talked() {
        val (day, n) = talkCount()
        val today = LocalDate.now().toString()
        prefs.state.edit().putString(Prefs.KAY_TALKS, "$today|${if (day == today) n + 1 else 1}").apply()
    }

    private fun talkCount(): Pair<String, Int> {
        val s = prefs.state.getString(Prefs.KAY_TALKS, "") ?: ""
        return s.substringBefore('|') to (s.substringAfter('|', "0").toIntOrNull() ?: 0)
    }

    fun chatSystem() = KayLines.chatSystem(name, age())

    /** The conversation so far and [said], as the request's text. */
    fun chatUser(said: String): String = buildString {
        val today = LocalDate.now()
        append("现在：").append(today).append(' ').append(KayLines.weekday(today.dayOfWeek)).append(' ')
            .append(LocalTime.now().toString().take(5)).append('\n')
        if (talk.isNotEmpty()) {
            append("刚才的对话：\n")
            for (t in talk.takeLast(10)) append(if (t.mine) "主人：" else "$name：").append(t.text).append('\n')
        }
        append("主人：").append(said)
    }

    /** 小K's answer, kept in the conversation. */
    fun heard(said: String, raw: String): String {
        val answer = raw.trim()
        talk += Turn(true, said); talk += Turn(false, answer)
        while (talk.size > 20) talk.removeAt(0)
        return answer
    }

    /** Forgets this conversation. */
    fun forgetTalk() = talk.clear()

}
