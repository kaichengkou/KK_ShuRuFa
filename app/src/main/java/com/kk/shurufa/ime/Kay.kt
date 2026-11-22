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
 * new day, talks when asked (接着聊, the 小K key), remembers a few things it was told, and answers a
 * few words on the space key (晚安, 生日快乐 …).
 *
 * The rules that keep it from being a nuisance: one line a day, never a question, gone at the
 * first touch; never in passwords, addresses or 临时模式; nothing typed is ever sent anywhere
 * unless the person is talking to it. With an AI service it writes its own line (one request a
 * day, asked the evening before) and talks; without one it has its own lines and its face.
 */
class Kay(private val context: Context, private val prefs: Prefs) {
    val name get() = prefs.kayName

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
            Llm.completeQuick(prefs, target, KayLines.greetSystem(name), KayLines.greetUser(facts, notes().map { it.second }, recent))
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

    fun chatSystem() = KayLines.chatSystem(name, age(), notes().map { it.second })

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

    /** 小K's answer, cleaned; what it decided to remember (〔记住：…〕) is kept and taken out. */
    fun heard(said: String, raw: String): String {
        val keep = Regex("〔记住[:：]\\s*([^〕]+)〕").findAll(raw).map { it.groupValues[1].trim() }.toList()
        for (k in keep) remember(k)
        val answer = raw.replace(Regex("〔记住[:：][^〕]*〕"), "").trim()
        talk += Turn(true, said); talk += Turn(false, answer)
        while (talk.size > 20) talk.removeAt(0)
        return answer
    }

    // ---- what it remembers ----

    private val memoryFile get() = File(context.filesDir, "kay_memory.txt")

    /** (day, note), oldest first; a month at most, thirty at most. */
    fun notes(): List<Pair<String, String>> {
        if (!memoryFile.isFile) return emptyList()
        val since = LocalDate.now().minusDays(30).toString()
        return memoryFile.readLines().mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 && it[0] >= since }?.let { it[0] to it[1] } }
    }

    fun remember(note: String) {
        if (note.isBlank() || note.length > 80 || prefs.incognito) return
        val list = (notes() + (LocalDate.now().toString() to note.replace('\n', ' '))).distinctBy { it.second }.takeLast(30)
        memoryFile.writeText(list.joinToString("") { "${it.first}\t${it.second}\n" })
    }

    fun forget(note: String) {
        val list = notes().filter { it.second != note }
        memoryFile.writeText(list.joinToString("") { "${it.first}\t${it.second}\n" })
    }

    fun forgetAll() { memoryFile.delete(); talk.clear() }

    // ---- the space key: 小K's little window ----

    private val heardText = StringBuilder()

    /**
     * What was just typed ([text], committed): a few words get an answer on the space key
     * (晚安 → 晚安 ( ˘ω˘ )). Returns the answer, or null.
     */
    fun typed(text: String): String? {
        if (!prefs.kayEggs) return null
        heardText.append(text)
        if (heardText.length > 24) heardText.delete(0, heardText.length - 24)
        val tail = heardText.toString().lowercase()
        // called by its name
        if (name.length >= 2 && tail.trimEnd(*TRAIL).endsWith(name.lowercase())) { heardText.setLength(0); return "在呢 (・ω・)" }
        val hit = KayLines.EGGS.firstOrNull { (keys, _) -> keys.any { tail.endsWith(it) || tail.trimEnd(*TRAIL).endsWith(it) } } ?: return null
        heardText.setLength(0)
        return hit.second
    }

    /** The first key after midnight, once a night: 夜深了. */
    fun lateNight(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): String? {
        if (!prefs.kayEggs || now.hour !in 0..4) return null
        if (prefs.state.getString(Prefs.KAY_NIGHT, "") == today.toString()) return null
        prefs.state.edit().putString(Prefs.KAY_NIGHT, today.toString()).apply()
        return "夜深了 (－_－)zZ"
    }

    private companion object {
        val TRAIL = charArrayOf('！', '!', '。', '.', '~', '～', ' ', '，', ',', '❤', '♥')
    }
}
