package com.kk.shurufa.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.FamilyRows
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.SymbolCategory

/**
 * 符号: the symbol panel (最近 and the layout's categories down the side), 锁定 for typing several
 * in a row, and 记住符号 (the !#1 key goes back to the symbols when they were used a moment ago).
 */
class SymbolsFeature(private val host: Host) {
    private val ctx get() = host.context
    private val prefs get() = host.prefs

    /** The categories, from the layout files. */
    var categories: List<SymbolCategory> = emptyList()
    /** 锁定: a symbol typed keeps the panel open. */
    var locked = false
        private set
    /** When the symbols were last left for something outside their family (0: they were not). */
    var lastUsedAt = 0L

    private var cat = ""
    private var shown: List<Output> = emptyList()
    private val recent = mutableListOf<String>()

    private lateinit var rail: GridPanel
    private lateinit var cells: GridPanel
    private lateinit var bottom: KeyboardView

    init {
        recent += prefs.state.getString(Prefs.RECENT_SYMBOLS, "")!!.split('\u0001').filter { it.isNotEmpty() }
    }

    fun build(): View {
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        rail = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; columns = 1; rowHeightDp = 38f; textSp = 14f; cellStyle = GridPanel.CellStyle.RAIL
            onItem = { i -> cat = catIds().getOrElse(i) { "recent" }; refresh() }
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
        }
        cells = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; cellStyle = GridPanel.CellStyle.SOFT; rowHeightDp = 42f
            emptyText = "用过的符号会出现在这里"
            onItem = { i -> shown.getOrNull(i)?.let { type(it) } }
            onFeedback = { host.haptics.play(HapticEvent.LETTER) }
        }
        top.addView(rail, LinearLayout.LayoutParams((62f * ctx.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(cells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bottom = host.newKeyboard()
        bottom.setRows(listOf(FamilyRows.symbols()))
        panel.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return panel
    }

    private fun catIds() = listOf("recent") + categories.map { it.id }

    fun refresh() {
        if (categories.isEmpty()) return
        // 最近 holds what was typed; with nothing typed yet the panel opens on the first category
        if (cat.isEmpty()) cat = if (recent.isNotEmpty()) "recent" else categories.first().id
        val idx = catIds().indexOf(cat).coerceAtLeast(0)
        val c = categories.getOrNull(idx - 1)
        shown = if (idx == 0) recent.map { Output.of(it) } else c?.items.orEmpty()
        val wide = c?.wide == true
        rail.selectedIndex = idx
        rail.setItems(listOf("最近") + categories.map { it.name }, resetScroll = false)
        cells.columns = if (wide) 3 else 6
        cells.textSp = if (wide) 14f else 18.5f
        cells.marks = emptySet()
        cells.setItems(shown.map { it.label })
        bottom.lockOn = locked
    }

    /** 锁定 on or off. */
    fun toggleLock() {
        locked = !locked
        bottom.lockOn = locked
        host.notice(if (locked) "已锁定：可以连续输入符号" else "已解锁：点一个符号就回到字母")
    }

    /** The symbols were used a moment ago: !#1 goes back to them. */
    fun recentlyUsed() = lastUsedAt > 0 && SystemClock.uptimeMillis() - lastUsedAt < prefs.symbolMemorySec * 1000L

    private fun type(o: Output) {
        host.finishComposing()
        lastUsedAt = SystemClock.uptimeMillis()
        host.editor.type(o.text)
        recent.remove(o.text)
        recent.add(0, o.text)
        while (recent.size > 30) recent.removeAt(recent.size - 1)
        if (!prefs.incognito) prefs.state.edit().putString(Prefs.RECENT_SYMBOLS, recent.joinToString("\u0001")).apply()
        if (!locked && prefs.symbolAutoReturn) host.show(Panel.LETTERS)
    }
}
