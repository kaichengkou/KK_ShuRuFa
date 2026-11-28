package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kk.shurufa.rime.RimeBridge

/**
 * 学习延后: Rime does not learn at the moment of a commit (the keyboard's session has
 * _kk_no_learning on); a commit is taught a few seconds later, if it stayed. Deleting into a word
 * just committed (删除, 撤销, 上滑清空) drops it, so a slip and its correction teach nothing.
 * What the app clears itself (a chat app after 发送) is not a deletion and is still learned.
 *
 * Teaching is [RimeBridge.learn]: the same pinyin typed again in a session of its own with
 * learning on, choosing until the same text comes out, which is exactly what Rime would have
 * learned at the time.
 */
class DeferredLearning(private val delayMs: Long = DELAY_MS) {
    /** [before]: the text just before it in the field, for 联想记忆; [input] empty for an association picked (nothing for Rime). */
    class Item(val input: String, val text: String, val at: Long, val before: String = "")

    /** A commit that stayed, as it is taught (on the main thread). */
    var onTaught: ((Item) -> Unit)? = null

    private val items = ArrayDeque<Item>()
    private val main = Handler(Looper.getMainLooper())
    private val due = Runnable { flush(all = false) }

    /** [text] was committed from [input] (the pinyin syllables behind it, joined). */
    fun committed(input: String, text: String, before: String = "") {
        if (text.isEmpty() || (input.isEmpty() && before.isEmpty())) return
        items.addLast(Item(input, text, SystemClock.uptimeMillis(), before))
        while (items.size > 30) teach(listOf(items.removeFirst()))
        main.removeCallbacks(due)
        main.postDelayed(due, delayMs)
    }

    /**
     * A backspace is about to delete the character before the caret: when that is the end of the
     * latest commit (what is [before] the caret ends with it), the commit is dropped.
     */
    fun deleting(before: (Int) -> String) {
        val last = items.lastOrNull() ?: return
        if (before(last.text.length) == last.text) items.removeLast()
    }

    /** 撤销 took the latest input back. */
    fun undone() { items.removeLastOrNull() }

    /** The whole field was cleared by the person (上滑清空): nothing pending was meant. */
    fun clearedAll() { items.clear(); main.removeCallbacks(due) }

    /** 临时模式, or a field that is not to be learned from: what is pending is forgotten. */
    fun dropAll() = clearedAll()

    /** Teaches what is old enough, or everything (the keyboard is going away). */
    fun flush(all: Boolean) {
        val now = SystemClock.uptimeMillis()
        val ready = ArrayList<Item>()
        while (items.isNotEmpty() && (all || now - items.first().at >= delayMs)) ready += items.removeFirst()
        if (ready.isNotEmpty()) teach(ready)
        main.removeCallbacks(due)
        if (items.isNotEmpty()) main.postDelayed(due, (delayMs - (now - items.first().at)).coerceAtLeast(500))
    }

    /** On Rime's own thread, after any deploy in progress; nothing while the engine is not running. */
    private fun teach(list: List<Item>) {
        list.forEach { onTaught?.invoke(it) }
        val rime = list.filter { it.input.isNotEmpty() }
        // one job each: the engine's lock is let go between them, so keys typed meanwhile (in the
        // next field, right after leaving this one) do not wait for the whole list
        for (i in rime) com.kk.shurufa.rime.RimeEngine.whenReady { runCatching { RimeBridge.learn(i.input, i.text) } }
    }

    val pending get() = items.size

    companion object {
        /** How long a commit has to stay before it is learned. */
        const val DELAY_MS = 8_000L
    }
}
