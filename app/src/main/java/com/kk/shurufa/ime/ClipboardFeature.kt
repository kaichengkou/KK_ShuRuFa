package com.kk.shurufa.ime

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.keyboard.ClipStrip
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.SheetHandle
import java.io.File

/**
 * 剪贴板: what was copied is remembered (sensitive copies are not), offered once as a paste chip
 * on the bar, and listed in the panel — 最近 as a sideways strip, 收藏 below. A tap pastes;
 * everything else is in the long-press menu. The grab bar (or the toolbar above it) drags the
 * panel taller.
 */
class ClipboardFeature(private val host: Host) {
    private val ctx get() = host.context
    private val prefs get() = host.prefs
    val clips = ClipHistory(File(ctx.filesDir, "clipboard.txt")) { prefs.clipKeep }

    private var pending: String? = null
    private var pendingAt = 0L

    private val manager get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onCopied() }

    fun register() = manager.addPrimaryClipChangedListener(listener)
    fun unregister() = manager.removePrimaryClipChangedListener(listener)

    private fun onCopied() {
        val clip = manager.primaryClip ?: return
        val sensitive = Build.VERSION.SDK_INT >= 33 && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        if (sensitive) return
        val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
        if (text.isBlank()) return
        clips.add(text)
        if (prefs.clipSuggest) { pending = text; pendingAt = SystemClock.uptimeMillis() }
        if (built && host.panel == Panel.CLIPBOARD) refresh()
        host.syncBar()
    }

    /** The paste chip's text while it is fresh (a minute and a half after copying). */
    fun pendingChip(): String? = pending?.takeIf { SystemClock.uptimeMillis() - pendingAt < 90_000 }

    fun dismissChip() { pending = null }

    // ---- the panel ----
    private var built = false
    lateinit var panel: LinearLayout
        private set
    lateinit var handle: SheetHandle
        private set
    private lateinit var strip: ClipStrip
    private lateinit var favs: GridPanel
    private var recent: List<ClipHistory.Entry> = emptyList()
    private var favItems: List<ClipHistory.Entry> = emptyList()

    /** Builds the panel for a new input view; [newHandle] makes a grab bar wired to the host. */
    fun build(newHandle: () -> SheetHandle): View {
        val dp = ctx.resources.displayMetrics.density
        panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val fb: (String) -> Unit = { k -> host.haptics.play(when (k) { "popup" -> HapticEvent.POPUP; "tick" -> HapticEvent.OPTION; else -> HapticEvent.LETTER }) }
        strip = ClipStrip(ctx, prefs).apply {
            theme = host.kbTheme; overlay = host.overlay
            emptyText = "复制过的文字会出现在这里"
            onFeedback = fb
            onTap = { i -> recent.getOrNull(i)?.let { paste(it.text) } }
            longOptions = { i -> recent.getOrNull(i)?.let { e ->
                listOf(Output("取消", "§cancel"), Output(if (e.pinned) "取消收藏" else "收藏", "§pin"), Output("删除", "§del"), Output("清空最近", "§clear"))
            }.orEmpty() }
            onLongOption = { i, o -> recent.getOrNull(i)?.let { option(it, o.text) } }
        }
        favs = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; overlay = host.overlay
            cellStyle = GridPanel.CellStyle.CARD; rowHeightDp = 66f
            emptyText = "长按「最近」里的一条，把它收藏到这里"
            onFeedback = fb
            onItem = { i -> favItems.getOrNull(i)?.let { paste(it.text) } }
            longOptions = { i -> if (i in favItems.indices) listOf(Output("取消", "§cancel"), Output("取消收藏", "§pin"), Output("移到最前", "§top"), Output("删除", "§del")) else emptyList() }
            onLongOption = { i, o -> favItems.getOrNull(i)?.let { option(it, o.text) } }
        }
        applyLook()
        handle = newHandle()
        // the strip and the gaps between favorites resize the panel too, not only the grab bar
        val drag: (Int, Float, Float) -> Unit = { phase, y, v -> when (phase) { 0 -> handle.begin(y); 1 -> handle.drag(y); else -> handle.end(v) } }
        strip.onVerticalDrag = drag
        favs.onGapDrag = drag
        panel.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (118 * dp).toInt()))
        panel.addView(favs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        built = true
        return panel
    }

    /** 收藏的列数, 滑动阻尼 and the text size that goes with the columns. */
    fun applyLook() {
        favs.friction = prefs.clipFriction
        favs.columns = prefs.clipColumns
        // narrower cards use slightly smaller text so a line still holds a few words
        favs.textSp = when (prefs.clipColumns) { 2 -> 13.5f; 3 -> 12.5f; else -> 11.5f }
    }

    fun refresh(keep: Boolean = false) {
        if (!built) return
        recent = clips.entries()
        favItems = clips.favorites()
        strip.setCards(recent.map { ClipStrip.Card(it.text, ClipHistory.kind(it.text), it.pinned) }, keep)
        favs.header = if (favItems.isEmpty()) "收藏" else "收藏 ${favItems.size}"
        favs.tags = favItems.map { ClipHistory.kind(it.text) }
        favs.setItems(favItems.map { short(it.text) }, resetScroll = !keep)
    }

    private fun short(t: String): String {
        val s = t.replace(SPACES, " ").trim()
        return if (s.length > prefs.clipMaxChars) s.take(prefs.clipMaxChars) + "…" else s
    }

    fun paste(text: String) {
        host.finishComposing()
        host.editor.type(text, pairable = false)
        pending = null
    }

    private fun option(e: ClipHistory.Entry, op: String) {
        when (op) {
            "§pin" -> clips.togglePin(e.text)
            "§del" -> clips.remove(e.text)
            "§clear" -> clips.clearUnpinned()
            "§top" -> clips.moveToFront(e.text)
            else -> return
        }
        host.haptics.play(HapticEvent.GESTURE)
        refresh(keep = op != "§clear")
    }

    private companion object { val SPACES = Regex("\\s+") }
}

/**
 * Recent clipboard texts, newest first; pinned entries stay when the rest is cleared. Changes are
 * written to disk off the main thread, the newest state winning.
 */
class ClipHistory(private val file: File, private val keep: () -> Int) {
    data class Entry(val text: String, val pinned: Boolean)

    private val list = mutableListOf<Entry>()

    init {
        if (file.isFile) file.readText().split('\u0002').filter { it.length > 1 }.forEach {
            list += Entry(it.substring(1), it[0] == '1')
        }
    }

    private fun save() {
        val snapshot = list.joinToString("\u0002") { (if (it.pinned) "1" else "0") + it.text }
        Background.run { file.writeText(snapshot) }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        val pinned = list.firstOrNull { it.text == text }?.pinned ?: false
        list.removeAll { it.text == text }
        list.add(0, Entry(text, pinned))
        while (list.count { !it.pinned } > keep()) list.remove(list.last { !it.pinned })
        save()
    }

    fun togglePin(text: String) {
        val i = list.indexOfFirst { it.text == text }
        if (i >= 0) { list[i] = list[i].copy(pinned = !list[i].pinned); save() }
    }

    fun remove(text: String) { list.removeAll { it.text == text }; save() }
    fun moveToFront(text: String) {
        val i = list.indexOfFirst { it.text == text }
        if (i > 0) { list.add(0, list.removeAt(i)); save() }
    }
    fun clearUnpinned() { list.removeAll { !it.pinned }; save() }
    /** Everything, newest first; favorites stay where they were copied. */
    fun entries(): List<Entry> = list.toList()
    fun favorites(): List<Entry> = list.filter { it.pinned }

    companion object {
        private val LINK = Regex("^(https?://|www\\.)\\S+$")
        private val MAIL = Regex("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$")
        private val CODE = Regex("^\\d{4,8}$")
        private val PHONE = Regex("^\\+?[\\d\\s()-]{7,18}$")

        /** A short tag for what a clip looks like. */
        fun kind(t: String): String {
            val s = t.trim()
            return when {
                LINK.matches(s) -> "链接"
                MAIL.matches(s) -> "邮箱"
                CODE.matches(s) -> "验证码"
                PHONE.matches(s) && s.count { it.isDigit() } >= 7 -> "号码"
                else -> ""
            }
        }
    }
}
