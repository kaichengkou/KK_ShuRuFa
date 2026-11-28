package com.kk.shurufa.ime

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.SheetHandle
import com.kk.shurufa.keyboard.TabStrip

/**
 * 展开候选: the opened candidate grid, with 声调筛选's rows above it and 收起 · 删除 · 重输 beside it.
 * Picking, the long-press menu and the tones go back to the input method.
 */
internal class CandidatePanel(
    private val host: Host,
    private val toneFilter: ToneFilter,
    /** The grab bar that resizes the grid. */
    val handle: SheetHandle,
    private val pick: (Int) -> Unit,
    private val options: (Int) -> List<Output>,
    private val onOption: (Int, Output) -> Unit,
    private val onTone: (Int) -> Unit,
) {
    private val ctx get() = host.context
    private val prefs get() = host.prefs
    private val grid: GridPanel
    private val toneTabs: TabStrip
    private val syllableTabs: TabStrip
    val view: LinearLayout

    init {
        grid = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; cellStyle = GridPanel.CellStyle.FLAT; columns = 8; rowHeightDp = 50f; textSp = 19f
            highlightFirst = true; overlay = host.overlay
            onItem = { i -> pick(i) }
            onFeedback = { k -> host.haptics.play(if (k == "popup") HapticEvent.POPUP else HapticEvent.LETTER) }
            longOptions = { i -> options(i) }
            onLongOption = { i, o -> onOption(i, o) }
        }
        val side = host.newKeyboard().apply {
            heightScale = 0.9f
            setRows(listOf(
                listOf(Key(fn = "collapse", label = "收起")),
                listOf(Key(fn = "backspace")),
                listOf(Key(fn = "reinput", label = "重输")),
            ))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(side, LinearLayout.LayoutParams((68f * ctx.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        // 声调筛选, in the opened candidates only: the bar stays as it was
        toneTabs = TabStrip(ctx, prefs).apply {
            theme = host.kbTheme; compact = true
            onFeedback = { host.haptics.play(HapticEvent.OPTION) }
            onTab = { t -> onTone(t.toInt()) }
            onVerticalDrag = { phase, y, v -> when (phase) { 0 -> handle.begin(y); 1 -> handle.drag(y); else -> handle.end(v) } }
        }
        view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        view.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // with several syllables, a row above picks which one the tones are for
        syllableTabs = TabStrip(ctx, prefs).apply {
            theme = host.kbTheme; compact = true
            onFeedback = { host.haptics.play(HapticEvent.OPTION) }
            onTab = { t -> toneFilter.syllable = t.toInt(); syncTones() }
            onVerticalDrag = { phase, y, v -> when (phase) { 0 -> handle.begin(y); 1 -> handle.drag(y); else -> handle.end(v) } }
        }
        view.addView(syllableTabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(toneTabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Shows [cands] with their [notes]. */
    fun fill(cands: List<String>, notes: List<String>) {
        grid.notes = notes
        grid.setItems(cands)
    }

    /** The tone rows of the opened candidates, or none (声调筛选 off, nothing to narrow). */
    fun syncTones() {
        val rows = if (prefs.toneFilter) toneFilter.rows() else null
        syllableTabs.visibility = if (rows?.syllables != null) View.VISIBLE else View.GONE
        toneTabs.visibility = if (rows != null) View.VISIBLE else View.GONE
        if (rows == null) return
        rows.syllables?.let { syllableTabs.setTabs(it, rows.syllable) }
        toneTabs.setTabs(rows.tones, rows.tone)
    }
}

/** What holding a word on the bar or in the grid offers. */
internal object CandidateMenus {
    /** [word] as a menu names it: six characters at most. */
    fun menuName(word: String) =
        if (word.codePointCount(0, word.length) > 6) word.substring(0, word.offsetByCodePoints(0, 5)) + "…" else word

    /** An association held: put it in as it is, or never offer it again (and forget it was learned). */
    fun prediction(word: String): List<Output> {
        val shown = menuName(word)
        return listOf(Output("取消", "§cancel"), Output("上屏「$shown」", "§type"), Output("不再联想「$shown」", "§mute"))
    }

    /** One of Rime's candidates held: a character of it (以词定字), or the word lowered or taken off. */
    fun zh(word: String): List<Output> {
        val shown = menuName(word)
        val chars = word.codePoints().toArray().filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        // 以词定字: a character of a word, the rest of the pinyin stays
        val pick = if (word.codePointCount(0, word.length) >= 2 && chars.size >= 2) listOf(
            Output("取首字「${String(Character.toChars(chars.first()))}」", "§first"),
            Output("取末字「${String(Character.toChars(chars.last()))}」", "§last")) else emptyList()
        return listOf(Output("取消", "§cancel")) + pick + Output("不再优先「$shown」", "§forget") + Output("不再出现「$shown」", "§mute")
    }
}
