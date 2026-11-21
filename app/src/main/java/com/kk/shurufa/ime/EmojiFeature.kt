package com.kk.shurufa.ime

import android.content.Context
import android.graphics.Paint
import android.net.ConnectivityManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output

/**
 * 表情: the emoji panel (查找 · 最近 · the Unicode groups · 颜文字), skin tones on long-press, the
 * recent lists, and 表情查找 (the keyboard types a word, the bar shows matching emoji). The data
 * is read and checked against the phone's font in the background; only emoji the phone can draw
 * are shown.
 */
class EmojiFeature(private val host: Host) {
    private val ctx get() = host.context
    private val prefs get() = host.prefs
    val data = EmojiData(ctx)

    /** Groups as the phone can draw them. */
    private var groups: List<Pair<String, List<String>>> = emptyList()
    private var drawable: Set<String> = emptySet()
    private var kaomoji: List<String> = emptyList()
    private val recent = mutableListOf<String>()
    private val recentKao = mutableListOf<String>()

    init {
        val st = prefs.state
        recent += st.getString(Prefs.RECENT_EMOJI, "")!!.split(' ').filter { it.isNotEmpty() }
        recentKao += st.getString(Prefs.RECENT_KAOMOJI, "")!!.split('\u0001').filter { it.isNotEmpty() }
    }

    private class Loaded(val groups: List<Pair<String, List<String>>>, val kaomoji: List<String>)

    /** Reads the data and finds what the font can draw, off the main thread. */
    fun load() {
        Background.load({
            runCatching { data.load() }
            val glyphs = Paint()
            val g = data.groups.map { (n, l) -> n to l.filter { glyphs.hasGlyph(it) } }.filter { it.second.isNotEmpty() }
            val k = runCatching { ctx.assets.open("data/kaomoji.txt").bufferedReader().readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
            Loaded(g, k)
        }) { l ->
            groups = l.groups
            drawable = l.groups.flatMapTo(HashSet()) { it.second }
            kaomoji = l.kaomoji
            if (built && host.panel == Panel.EMOJI) refresh()
        }
    }

    /** 自动更新: at most once a week, on an unmetered network, when the keyboard starts. */
    fun maybeUpdate() {
        val now = System.currentTimeMillis()
        if (!prefs.emojiAutoUpdate || now - prefs.state.getLong(Prefs.EMOJI_CHECKED, 0L) < 7L * 24 * 3600 * 1000) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.activeNetwork == null || cm.isActiveNetworkMetered) return
        prefs.state.edit().putLong(Prefs.EMOJI_CHECKED, now).apply()
        val current = data.version
        Background.download({ runCatching { EmojiData.update(ctx, current) }.getOrNull() }) { v -> if (v != null) load() }
    }

    // ---- the panel ----
    private var built = false
    private lateinit var rail: GridPanel
    private lateinit var cells: GridPanel
    /** Tab of the emoji panel: 0 最近, 1..n the groups, n+1 颜文字. */
    private var tab = 0
    private var shown: List<String> = emptyList()

    fun build(): View {
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        rail = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; columns = 1; rowHeightDp = 38f; textSp = 14f; cellStyle = GridPanel.CellStyle.RAIL
            // the first item is 查找, the rest are tabs
            onItem = { i -> if (i == 0) startSearch() else { tab = i - 1; refresh() } }
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
        }
        cells = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; columns = 8; rowHeightDp = 42f; textSp = 23f; cellStyle = GridPanel.CellStyle.PLAIN
            emptyText = "发过的表情会出现在这里"
            onItem = { i -> shown.getOrNull(i)?.let { type(it) } }
            onFeedback = { k -> host.haptics.play(if (k == "popup") HapticEvent.POPUP else HapticEvent.LETTER) }
            overlay = host.overlay
            // 长按选肤色: the emoji and its forms, six to a row (one row per man/woman form)
            longOptionRows = { i -> shown.getOrNull(i)?.let { variantRows(it) }.orEmpty() }
            onLongOption = { _, o -> type(o.text); refreshKeepScroll() }
        }
        val dp = ctx.resources.displayMetrics.density
        top.addView(rail, LinearLayout.LayoutParams((66 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(cells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val bottom = host.newKeyboard()
        bottom.setRows(listOf(listOf(
            Key(fn = "abc", label = "ABC", width = 1.5f), Key(fn = "space", width = 4f),
            Key(fn = "backspace", width = 1.5f), Key(fn = "enter", width = 1.5f),
        )))
        panel.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        built = true
        return panel
    }

    fun refresh() {
        if (!built) return
        val kaoTab = groups.size + 1
        tab = tab.coerceIn(0, kaoTab)
        rail.selectedIndex = tab + 1
        rail.railIcons = listOf("search")
        rail.setItems(listOf("查找", "最近") + groups.map { it.first } + "颜文字", resetScroll = false)
        val kao = tab == kaoTab
        shown = when {
            tab == 0 -> recent.toList()
            kao -> (recentKao + kaomoji).distinct()
            else -> groups.getOrNull(tab - 1)?.second.orEmpty()
        }
        // kaomoji are wide: three to a row, in text size
        cells.columns = if (kao) 3 else 8
        cells.textSp = if (kao) 14f else 23f
        cells.rowHeightDp = if (kao) 44f else 42f
        // cells with skin tones or man/woman forms get a corner mark
        cells.marks = if (kao) emptySet() else shown.indices.filter { data.variants.containsKey(base(shown[it])) }.toSet()
        cells.setItems(shown)
    }

    /** Re-reads the current tab without moving the grid (after a tone was chosen). */
    private fun refreshKeepScroll() {
        shown = when (tab) {
            0 -> recent.toList()
            in 1..groups.size -> groups[tab - 1].second
            else -> return
        }
        cells.setItems(shown, resetScroll = false)
    }

    /** Sends [e] and remembers it among the recent emoji (or kaomoji). */
    fun type(e: String) {
        host.finishComposing()
        host.editor.type(e, pairable = false)
        if (e in kaomoji || recentKao.contains(e)) {
            recentKao.remove(e); recentKao.add(0, e)
            while (recentKao.size > 12) recentKao.removeAt(recentKao.size - 1)
            if (!prefs.incognito) prefs.state.edit().putString(Prefs.RECENT_KAOMOJI, recentKao.joinToString("\u0001")).apply()
            return
        }
        recent.remove(e); recent.add(0, e)
        while (recent.size > 32) recent.removeAt(recent.size - 1)
        if (!prefs.incognito) prefs.state.edit().putString(Prefs.RECENT_EMOJI, recent.joinToString(" ")).apply()
    }

    /** The base emoji a shown one belongs to (a recent skin tone maps back to its base). */
    private fun base(e: String): String = if (data.variants.containsKey(e)) e
        else data.variants.entries.firstOrNull { e in it.value }?.key ?: e

    private fun variantRows(shown: String): List<List<Output>> {
        val b = base(shown)
        val glyphs = Paint()
        val forms = data.variants[b].orEmpty().filter { glyphs.hasGlyph(it) }
        if (forms.isEmpty()) return emptyList()
        return (listOf(b) + forms).chunked(6).map { row -> row.map { Output.of(it) } }
    }

    // ---- 表情查找 ----

    /** Emoji the phone can draw whose names match [query]. */
    fun search(query: String): List<String> = data.search(query, 160).filter { it in drawable }.take(120)

    /**
     * Opens the letters with the bar showing matching emoji. The query starts from what is
     * selected, or the last word before the cursor, so one tap already finds related emoji.
     */
    fun startSearch() {
        host.finishComposing()
        host.openSink(Sink.EMOJI, contextWord())
    }

    private fun contextWord(): String {
        host.editor.selectedText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(12) }
        val before = host.editor.textBefore(24).trimEnd()
        if (before.isEmpty()) return ""
        return if (isCjk(before.last())) before.takeLastWhile { isCjk(it) }.takeLast(6)
        else before.takeLastWhile { it.isLetter() }.takeLast(20)
    }
}

internal fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
