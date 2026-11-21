package com.kk.shurufa.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.keyboard.ClipStrip
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.SheetHandle
import com.kk.shurufa.keyboard.TabStrip
import java.io.File

/**
 * 剪贴板: what was copied is remembered (sensitive copies are not), offered once as a paste chip
 * on the bar, and listed in the panel — 最近 as a sideways strip, 收藏 below. Once there is
 * something to sort, a row of filters (验证码 · 链接 · 号码 …) narrows both to one kind and shows
 * only the part that matters: the code out of the message, the link out of the text. A tap
 * pastes; everything else is in the long-press menu. The grab bar (or the toolbar above it) drags
 * the panel taller.
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
        val item = clip.getItemAt(0) ?: return
        val uri = item.uri
        if (uri != null && clip.description.hasMimeType("image/*")) {
            // our own picture put back on the clipboard for the app's paste: already in the list
            if (uri.authority == images.authority) return
            val mime = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") } ?: "image/png"
            Background.load({ images.save(uri, mime) }) { entry -> if (entry != null) added(entry) }
            return
        }
        val text = item.coerceToText(ctx)?.toString() ?: return
        if (text.isBlank()) return
        added(text)
    }

    private fun added(text: String) {
        clips.add(text)
        images.prune(clips.entries().map { it.text })
        if (prefs.clipSuggest) { pending = text; pendingAt = SystemClock.uptimeMillis() }
        if (built && host.panel == Panel.CLIPBOARD) refresh()
        host.syncBar()
    }

    /** Copied pictures, kept as files beside the history, with small thumbnails for the panel. */
    private val images = ClipImages(ctx)

    /** The paste chip's text while it is fresh (a minute and a half after copying). */
    fun pendingChip(): String? = pending?.takeIf { SystemClock.uptimeMillis() - pendingAt < 90_000 }

    fun dismissChip() { pending = null }

    /**
     * What the paste chip shows (its text and kind) and pastes: for a message with a code in it,
     * only the code — the chip of an SMS that just came in offers 482913, not the whole message.
     */
    fun chip(): Pair<String, String>? {
        val t = pendingChip() ?: return null
        val k = ClipHistory.classify(t)
        return when (k?.name) {
            "图片" -> "" to "图片"
            "验证码" -> k.value to k.name
            else -> t to k?.name.orEmpty()
        }
    }

    fun pasteChip() {
        val t = pendingChip() ?: return
        paste(ClipHistory.classify(t)?.takeIf { it.name == "验证码" }?.value ?: t)
    }

    // ---- the panel ----
    private var built = false
    lateinit var panel: LinearLayout
        private set
    lateinit var handle: SheetHandle
        private set
    private lateinit var filters: TabStrip
    private lateinit var strip: ClipStrip
    private lateinit var favs: GridPanel
    private var recent: List<ClipHistory.Entry> = emptyList()
    private var favItems: List<ClipHistory.Entry> = emptyList()
    /** The kind shown ("" for all). */
    private var filter = ""

    /** What a tap pastes: under a filter, only the part of the clip that matters. */
    private fun pasteText(e: ClipHistory.Entry): String =
        if (filter.isEmpty()) e.text else ClipHistory.classify(e.text)?.value ?: e.text

    /** Builds the panel for a new input view; [newHandle] makes a grab bar wired to the host. */
    fun build(newHandle: () -> SheetHandle): View {
        val dp = ctx.resources.displayMetrics.density
        panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val fb: (String) -> Unit = { k -> host.haptics.play(when (k) { "popup" -> HapticEvent.POPUP; "tick" -> HapticEvent.OPTION; else -> HapticEvent.LETTER }) }
        strip = ClipStrip(ctx, prefs).apply {
            theme = host.kbTheme; overlay = host.overlay
            emptyText = "复制过的文字会出现在这里"
            onFeedback = fb
            onTap = { i -> recent.getOrNull(i)?.let { paste(pasteText(it)) } }
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
            onItem = { i -> favItems.getOrNull(i)?.let { paste(pasteText(it)) } }
            longOptions = { i -> if (i in favItems.indices) listOf(Output("取消", "§cancel"), Output("取消收藏", "§pin"), Output("移到最前", "§top"), Output("删除", "§del")) else emptyList() }
            onLongOption = { i, o -> favItems.getOrNull(i)?.let { option(it, o.text) } }
        }
        filters = TabStrip(ctx, prefs).apply {
            theme = host.kbTheme; compact = true
            onFeedback = { host.haptics.play(HapticEvent.OPTION) }
            onTab = { k -> filter = k; refresh() }
        }
        applyLook()
        handle = newHandle()
        // the strip and the gaps between favorites resize the panel too, not only the grab bar
        val drag: (Int, Float, Float) -> Unit = { phase, y, v -> when (phase) { 0 -> handle.begin(y); 1 -> handle.drag(y); else -> handle.end(v) } }
        strip.onVerticalDrag = drag
        favs.onGapDrag = drag
        panel.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(filters, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val all = clips.entries()
        val kinds = all.associate { it.text to ClipHistory.classify(it.text) }
        // a filter for each kind there is; none at all while there is nothing to sort
        val present = ClipHistory.KINDS.filter { k -> kinds.values.any { it?.name == k } }
        if (filter.isNotEmpty() && filter !in present) filter = ""
        filters.visibility = if (present.isEmpty()) View.GONE else View.VISIBLE
        filters.setTabs(listOf("" to "全部") + present.map { it to it }, filter)
        fun shown(e: ClipHistory.Entry) = filter.isEmpty() || kinds[e.text]?.name == filter
        // under a filter a card shows what a tap pastes: the code, the link, the number
        fun text(e: ClipHistory.Entry) = if (filter.isEmpty()) e.text else kinds[e.text]?.value ?: e.text
        recent = all.filter(::shown)
        favItems = all.filter { it.pinned && shown(it) }
        strip.emptyText = if (filter.isEmpty()) "复制过的文字会出现在这里" else "最近没有复制过$filter"
        strip.setCards(recent.map { e ->
            if (ClipImages.isImage(e.text)) ClipStrip.Card("", "图片", e.pinned, isImage = true, image = thumb(e.text))
            else ClipStrip.Card(text(e), kinds[e.text]?.name.orEmpty(), e.pinned)
        }, keep)
        favs.header = if (favItems.isEmpty()) "收藏" else "收藏 ${favItems.size}"
        favs.tags = favItems.map { kinds[it.text]?.name.orEmpty() }
        favs.setItems(favItems.map { short(text(it)) }, resetScroll = !keep)
    }

    private fun short(t: String): String {
        if (ClipImages.isImage(t)) return "图片"
        val s = t.replace(SPACES, " ").trim()
        return if (s.length > prefs.clipMaxChars) s.take(prefs.clipMaxChars) + "…" else s
    }

    fun paste(text: String) {
        host.finishComposing()
        pending = null
        val img = ClipImages.parse(text)
        if (img == null) { host.editor.type(text, pairable = false); return }
        val (file, mime) = img
        val uri = images.uri(file) ?: return host.notice("图片已经不在了")
        if (host.editor.commitImage(uri, mime)) return
        // the field takes no pictures: put it on the clipboard for the app's own paste
        runCatching { manager.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "图片", uri)) }
        host.notice("这里不能直接插入图片，已复制，长按输入框粘贴")
    }

    /** Thumbnails decoded so far, by clip; a missing one is decoded in the background. */
    private val thumbs = HashMap<String, Bitmap?>()

    private fun thumb(text: String): Bitmap? {
        if (text in thumbs) return thumbs[text]
        thumbs[text] = null
        val (file, _) = ClipImages.parse(text) ?: return null
        Background.load({ images.thumbnail(file, (120 * ctx.resources.displayMetrics.density).toInt()) }) { b ->
            thumbs[text] = b
            if (b != null && built) refresh(keep = true)
        }
        return null
    }

    private fun option(e: ClipHistory.Entry, op: String) {
        when (op) {
            "§pin" -> clips.togglePin(e.text)
            "§del" -> { clips.remove(e.text); images.prune(clips.entries().map { it.text }) }
            "§clear" -> { clips.clearUnpinned(); images.prune(clips.entries().map { it.text }) }
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

    /** What a clip is (验证码, 链接 …) and the part of it that matters, e.g. the code in an SMS. */
    data class Kind(val name: String, val value: String)

    companion object {
        /** The kinds the panel can filter by, in the order its tabs show them. */
        val KINDS = listOf("图片", "验证码", "链接", "号码", "邮箱", "公式")

        private val LINK = Regex("(https?://|www\\.)[^\\s，。、；！？）)】」”\"'<>]+")
        private val MAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
        private val DIGITS = Regex("(?<![\\d.])\\d{4,8}(?![\\d.])")
        private val CODE_WORDS = Regex("验证码|校验码|动态码|确认码|驗證碼|激活码|code|otp|pin", RegexOption.IGNORE_CASE)
        private val MOBILE = Regex("(?<!\\d)(\\+?86[-\\s]?)?1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)")
        private val PHONE = Regex("^\\+?[\\d\\s()-]{7,18}$")
        private val LATEX = Regex("\\\\(frac|sqrt|sum|int|prod|lim|alpha|beta|gamma|theta|pi|cdot|times|leq|geq|neq|infty|left|right|mathbb|vec)\\b|[\\^_]\\{")
        private val MATH_SIGNS = Regex("[∫∑∏√π∞≤≥≠≈±×÷∂∇∈]")
        private val EQUATION = Regex("^[\\w\\s.+\\-*/^()=<>]{3,80}$")

        /** What [t] looks like, with the part of it worth pasting on its own; null for plain text. */
        fun classify(t: String): Kind? {
            if (ClipImages.isImage(t)) return Kind("图片", t)
            val s = t.trim()
            if (s.isEmpty()) return null
            // a code: the whole clip, or a 4–8 digit run in a message that says it is one
            if (s.length <= 8 && DIGITS.matches(s)) return Kind("验证码", s)
            if (s.length <= 300 && CODE_WORDS.containsMatchIn(s)) DIGITS.find(s)?.let { return Kind("验证码", it.value) }
            LINK.find(s)?.let { return Kind("链接", it.value.trimEnd('.', ',')) }
            MAIL.find(s)?.let { return Kind("邮箱", it.value) }
            MOBILE.find(s)?.let { return Kind("号码", it.value.filter { c -> c.isDigit() || c == '+' }) }
            if (PHONE.matches(s) && s.count { it.isDigit() } >= 7) return Kind("号码", s)
            if (s.length <= 400 && (LATEX.containsMatchIn(s) || MATH_SIGNS.containsMatchIn(s) ||
                    (EQUATION.matches(s) && '=' in s && s.any { it in "+-*/^" } && s.any { it.isDigit() }))) return Kind("公式", s)
            return null
        }

        /** A short tag for what a clip looks like. */
        fun kind(t: String): String = classify(t)?.name.orEmpty()
    }
}

/**
 * Pictures copied to the clipboard, as files in the app's storage. A picture stands in the
 * history as a marker line naming its file and type; files no entry names any more are deleted.
 * The keyboard hands them to apps through its own FileProvider.
 */
class ClipImages(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "clips")
    val authority = ctx.packageName + ".clips"

    /** Copies the picture at [uri] in; the history line for it, or null if it could not be read. */
    fun save(uri: android.net.Uri, mime: String): String? = runCatching {
        dir.mkdirs()
        val ext = mime.substringAfter('/').substringBefore('+').ifEmpty { "png" }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        // the same picture copied twice is one file
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(20) + "." + ext
        val f = File(dir, name)
        if (!f.exists()) f.writeBytes(bytes)
        MARK + name + "|" + mime
    }.getOrNull()

    fun uri(name: String): android.net.Uri? {
        val f = File(dir, name)
        if (!f.isFile) return null
        return runCatching { androidx.core.content.FileProvider.getUriForFile(ctx, authority, f) }.getOrNull()
    }

    /** A thumbnail about [size] pixels on its short side. */
    fun thumbnail(name: String, size: Int): Bitmap? = runCatching {
        val f = File(dir, name)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Deletes the files of pictures no longer in [entries]. */
    fun prune(entries: List<String>) {
        val keep = entries.mapNotNull { parse(it)?.first }.toSet()
        Background.run { dir.listFiles()?.forEach { if (it.name !in keep) it.delete() } }
    }

    companion object {
        private const val MARK = "\u0001img:"
        private const val MAX_BYTES = 20 * 1024 * 1024

        fun isImage(t: String) = t.startsWith(MARK)

        /** The file name and type a history line names, if it is a picture. */
        fun parse(t: String): Pair<String, String>? {
            if (!t.startsWith(MARK)) return null
            val rest = t.removePrefix(MARK)
            return rest.substringBefore('|') to rest.substringAfter('|', "image/png")
        }
    }
}
