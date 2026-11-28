package com.kk.shurufa

import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * A stand-in for librime in unit tests, where the native library cannot load: a tiny pinyin table
 * that behaves like Rime's session for what the typing path asks of it. Letters build the input,
 * a candidate picked consumes the pinyin it covers (你 for "ni" of "nihao" leaves "hao"), space
 * takes the first candidate, Return the letters as typed, backspace a letter. Tests use it through
 * [ShadowRimeBridge] with `@Config(shadows = [ShadowRimeBridge::class], instrumentedPackages = ["com.kk.shurufa.rime"])`.
 */
object FakeRime {
    class Entry(val pinyin: String, val text: String, val comment: String = "")

    /** Exact readings first, in order; a prefix of the input offers its own words after them. */
    val table = listOf(
        Entry("nihao", "你好"), Entry("nihao", "拟好"),
        Entry("ni", "你"), Entry("ni", "泥"), Entry("ni", "尼"),
        Entry("hao", "好"), Entry("hao", "号"),
        Entry("shijie", "世界"), Entry("shijie", "事件"),
        Entry("nihaoshijie", "你好世界"),
        // KK's corrector: p is a key away from o, and the candidate says so in its comment
        Entry("nihap", "你好", "〔kk:hap>hao〕"),
        Entry("zhongwen", "中文"),
    )

    var input = ""
    var chosen = ""
    private var committed = ""
    private var gen = 0
    val options = mutableMapOf<String, Boolean>()
    val learned = mutableListOf<Pair<String, String>>()

    fun reset() {
        input = ""; chosen = ""; committed = ""; gen++
        options.clear(); learned.clear()
    }

    /** Rime is up: what KkImeService waits for before it sends Chinese keys there. */
    fun ready() {
        reset()
        RimeEngine::class.java.getDeclaredField("state").apply { isAccessible = true }.set(null, RimeEngine.State.READY)
    }

    fun menu(): List<Entry> {
        if (input.isEmpty()) return emptyList()
        val out = table.filter { it.pinyin == input }.toMutableList()
        for (n in input.length - 1 downTo 1) out += table.filter { it.pinyin == input.substring(0, n) }
        return out.ifEmpty { listOf(Entry(input, input)) }
    }

    private fun changed() { gen++ }

    fun select(i: Int): Boolean {
        val e = menu().getOrNull(i) ?: return false
        chosen += e.text
        input = input.drop(e.pinyin.length)
        if (input.isEmpty()) { committed += chosen; chosen = "" }
        changed()
        return true
    }

    /** librime's commit_composition: each segment's highlighted candidate (the first), as Composition::GetCommitText does. */
    fun commitAll(): Boolean {
        if (input.isEmpty()) return false
        while (input.isNotEmpty()) select(0)
        return true
    }

    fun processKey(code: Int): Boolean {
        when {
            code in 'a'.code..'z'.code || (code == '\''.code && input.isNotEmpty()) -> { input += code.toChar(); changed(); return true }
            code == RimeBridge.XK_BackSpace -> {
                if (input.isEmpty()) return false
                input = input.dropLast(1)
                if (input.isEmpty()) chosen = ""
                changed(); return true
            }
            code == RimeBridge.XK_space -> return if (input.isEmpty()) false else select(0)
            code == RimeBridge.XK_Return -> {
                if (input.isEmpty()) return false
                committed += chosen + input; chosen = ""; input = ""; changed(); return true
            }
        }
        return false
    }

    fun preedit() = if (chosen.isNotEmpty() && input.isNotEmpty()) "$chosen $input" else chosen + input

    fun takeCommit(): String = committed.also { committed = "" }

    val generation get() = gen
}

@Implements(RimeBridge::class)
class ShadowRimeBridge {
    @Implementation fun startup(sharedDir: String, userDir: String, fullCheck: Boolean) {}
    @Implementation fun joinMaintenance() {}
    @Implementation fun shutdown() {}
    @Implementation fun processKey(keycode: Int, mask: Int): Boolean = FakeRime.processKey(keycode)
    @Implementation fun selectCandidateGlobal(index: Int): Boolean = FakeRime.select(index)
    @Implementation fun commitComposition(): Boolean = FakeRime.commitAll()
    @Implementation fun clearComposition() { FakeRime.input = ""; FakeRime.chosen = "" }
    @Implementation fun setOption(name: String, value: Boolean) { FakeRime.options[name] = value }
    @Implementation fun selectSchema(id: String): Boolean = true
    @Implementation fun candidates(limit: Int): Array<String> = FakeRime.menu().take(limit).map { it.text }.toTypedArray()
    @Implementation fun candidatesWithComments(limit: Int): Array<String> {
        val m = FakeRime.menu().take(limit)
        return (m.map { it.text } + m.map { it.comment }).toTypedArray()
    }
    @Implementation fun generation(): Int = FakeRime.generation
    @Implementation fun deleteCandidate(index: Int): Boolean = false
    @Implementation fun highlightCandidate(index: Int): Boolean = false
    @Implementation fun syncUserData(): Boolean = true
    @Implementation fun learn(input: String, text: String): Boolean { FakeRime.learned += input to text; return true }
    @Implementation fun setInput(input: String): Boolean { FakeRime.input = input; FakeRime.chosen = ""; return true }
    @Implementation fun caretPos(): Int = FakeRime.input.length
    @Implementation fun setCaretPos(pos: Int) {}
    @Implementation fun query(input: String, limit: Int): Array<String> =
        FakeRime.table.filter { it.pinyin == input }.take(limit).map { it.text }.toTypedArray()
    @Implementation fun t2s(text: String, config: String): String = text
    @Implementation fun commitText(): String = FakeRime.takeCommit()
    @Implementation fun preedit(): String = FakeRime.preedit()
    @Implementation fun input(): String = FakeRime.input
    @Implementation fun cursor(): Int = FakeRime.preedit().toByteArray(Charsets.UTF_8).size
}
