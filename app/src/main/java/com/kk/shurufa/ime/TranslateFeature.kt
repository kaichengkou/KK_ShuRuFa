package com.kk.shurufa.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Motion
import com.kk.shurufa.keyboard.PickList
import com.kk.shurufa.keyboard.TranslateStrip
import com.kk.shurufa.online.AiWriter
import com.kk.shurufa.online.Llm
import com.kk.shurufa.online.TranslateService

/**
 * 小K's strip, above the bar, in one of three ways (chosen on 小K's page, or in the strip's own
 * list): 翻译 (the text typed or brought in is translated as it is typed), 改写 (the text of the
 * field, or the selection, comes in; one of the ways of rewriting it, or an instruction, runs on
 * it) and 聊天 (what is typed is said to 小K). The keyboard types into the strip's text; ↵ does
 * what the strip is for: 上屏 the translation, run the rewrite (or use its answer), send.
 */
class TranslateFeature(private val host: Host, private val service: TranslateService) {
    private val prefs get() = host.prefs
    private val handler = Handler(Looper.getMainLooper())
    private val run = Runnable { translate() }
    /** The AI side (改写 and 聊天), set by the input method. */
    lateinit var ai: AiFeature

    /** "translate", "rewrite" or "chat"; [nextMode] is taken when the strip opens. */
    var mode = "translate"; private set
    var nextMode = "translate"

    private var to = "en"
    private var from = "auto"
    private var fromSelection = false
    private var result = ""
    private var resultFor = ""

    lateinit var strip: TranslateStrip
        private set

    fun build(): View {
        strip = TranslateStrip(host.context, prefs).apply {
            theme = host.kbTheme
            visibility = View.GONE
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onClose = { host.closeSink() }
            onButton = { b -> button(b) }
            onInsert = {
                val (t, field) = insertable()
                if (t.isNotEmpty()) {
                    // taken from the field: the answer will replace it there
                    fromField = field
                    host.query.setLength(0); host.query.append(t); sync()
                }
            }
            onSwap = {
                val src = if (from == "auto") TranslateService.guess(host.query.toString()) else from
                from = to; to = src
                // the previous result becomes the text to translate back
                if (result.isNotEmpty() && !fromSelection) { host.query.setLength(0); host.query.append(result) }
                resultFor = ""; sync()
            }
            onPick = { which, value -> if (which == "engine") pickEngine(value) else pickMenu(value) }
            onEngine = {
                if (Llm.targets(prefs).isEmpty()) host.notice("在 设置 → AI 服务 里填好 Key 后，可以用 AI")
                sync()
            }
            marks = { s -> ai.marked(s, rewriteBase) }
        }
        return strip
    }

    /** The strip opened: [selection] (if any) is the text, shown right away. */
    fun onOpened(selection: String?) {
        mode = nextMode
        nextMode = "translate"
        // what is said to 小K starts empty
        val selection = if (mode == "chat") { host.query.setLength(0); null } else selection
        to = prefs.translateTarget
        from = "auto"
        fromSelection = selection != null
        fromField = ""
        custom = false
        result = ""; resultFor = ""; rewriteBase = null; answer = ""; status = ""
        if (mode == "rewrite" && selection == null) {
            // 改写 works on what is in the field: it comes in at once
            val all = host.editor.allText() ?: (host.editor.textBefore(4000) + host.editor.textAfter(4000))
            if (all.isNotBlank()) { fromField = all; host.query.setLength(0); host.query.append(all) }
        }
        strip.visibility = View.VISIBLE
        if (Motion.on(prefs)) {
            strip.animate().cancel()
            strip.alpha = 0f; strip.translationY = strip.resources.displayMetrics.density * 24
            strip.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(prefs, Motion.PANEL))
                .setInterpolator(Motion.ENTER).withLayer().start()
        }
        strip.switched()
        sync()
    }

    fun onClosed() {
        commitWhenReady = false
        service.cancel()
        // a rewrite stops with its strip; 小K goes on answering (the conversation keeps it)
        if (mode == "rewrite") ai.cancel()
        rewriting = false
        handler.removeCallbacks(run)
        if (!::strip.isInitialized || strip.visibility != View.VISIBLE) return
        strip.closeLists()
        if (Motion.on(prefs)) {
            // it sinks away rather than vanishing
            strip.animate().cancel()
            strip.animate().alpha(0f).translationY(strip.resources.displayMetrics.density * 16).setDuration(Motion.dur(prefs, 160))
                .setInterpolator(Motion.EXIT).withLayer().withEndAction { strip.visibility = View.GONE; strip.alpha = 1f; strip.translationY = 0f }.start()
        } else strip.visibility = View.GONE
    }

    /** Another way, from the strip's list: the text stays, the answer goes. */
    private fun switchTo(m: String) {
        if (m == mode) return
        service.cancel(); ai.cancel(); handler.removeCallbacks(run)
        mode = m; custom = false
        result = ""; resultFor = ""; answer = ""; status = ""; rewriteBase = null
        if (m == "rewrite" && host.query.isBlank()) {
            val all = host.editor.allText() ?: host.editor.textBefore(4000)
            if (all.isNotBlank()) { fromField = all; host.query.append(all) }
        }
        if (m == "chat") { host.query.setLength(0); fromField = "" }
        strip.switched()
        host.haptics.play(HapticEvent.OPTION)
        sync()
        host.syncBar()
    }

    // ---- the list at the top left ----

    private fun menuItems(): List<PickList.Item> = when (mode) {
        "translate" -> TranslateService.LANGS.map { (id, name) -> PickList.Item("to:$id", "译成 $name", if (id == to) "当前" else "") } +
            (listOf("auto" to "自动识别") + TranslateService.LANGS).map { (id, name) -> PickList.Item("from:$id", "原文：$name", if (id == from) "当前" else "") } +
            switches()
        "rewrite" -> AiWriter.ACTIONS.filter { it.group == "改写" }.map { PickList.Item("act:${it.id}", it.title) } +
            PickList.Item("custom", "✎ 我来说怎么改") + switches()
        else -> listOf(PickList.Item("line", "一言", "今天那句"), PickList.Item("all", "看全部对话"), PickList.Item("clear", "清空对话")) + switches()
    }

    private fun switches() = listOf("rewrite" to "改写", "translate" to "翻译", "chat" to "聊天")
        .filter { it.first != mode }.map { (m, n) -> PickList.Item("mode:$m", n, "切换") }

    private fun pickMenu(id: String) {
        when {
            id.startsWith("mode:") -> switchTo(id.removePrefix("mode:"))
            id.startsWith("to:") -> { to = id.removePrefix("to:"); prefs.put(Prefs.TRANSLATE_TARGET, to); resultFor = ""; sync() }
            id.startsWith("from:") -> { from = id.removePrefix("from:"); resultFor = ""; sync() }
            id.startsWith("act:") -> { prefs.put(Prefs.KAY_REWRITE, id.removePrefix("act:")); custom = false; host.haptics.play(HapticEvent.OPTION); rewrite() }
            id == "custom" -> {
                // the text stays the field's; what is typed now says how to change it
                if (!custom) { custom = true; if (fromField.isEmpty()) fromField = host.query.toString(); host.query.setLength(0) }
                result = ""; rewriteBase = null; status = ""; sync(); host.syncBar()
            }
            id == "line" -> { ai.recallLine(); answer = ai.lastAnswer(); sync() }
            id == "all" -> ai.showConversation()
            id == "clear" -> { ai.forget(); answer = ""; sync() }
        }
    }

    private fun pickEngine(value: String) {
        if (mode == "translate") prefs.put(Prefs.TRANSLATE_ENGINE, value) else prefs.put(Prefs.AI_PROVIDER, value)
        resultFor = ""; sync()
    }

    // ---- what the strip shows ----

    /** 改写 by instruction: the query is the instruction, [fromField] the text. */
    private var custom = false
    private var rewriteBase: String? = null
    private var answer = ""
    private var status = ""
    private var rewriting = false

    private fun rewriteAction() = AiWriter.ACTIONS.firstOrNull { it.id == prefs.kayRewrite && it.group == "改写" } ?: AiWriter.ACTIONS.first()

    /** Updates the strip; a changed text is translated after a short pause in typing. */
    fun sync() {
        if (host.sink != Sink.TRANSLATE) return
        val q = host.query.toString()
        val st = strip
        st.mode = mode
        st.menuItems = menuItems()
        val aiTarget = Llm.resolve(prefs, prefs.aiProvider)
        when (mode) {
            "translate" -> {
                val src = if (from == "auto") TranslateService.guess(q) else from
                if (src == to) to = if (src == "zh") "en" else "zh"
                val fromName = if (from == "auto") (if (q.isBlank()) "自动" else TranslateService.name(src)) else TranslateService.name(from)
                st.modeLabel = "翻译 · $fromName → ${TranslateService.name(to)}"
                st.showSwap = true
                st.menuCurrent = "to:$to"
                st.engineName = service.engineName()
                st.engine = service.engine
                st.engines = listOf(PickList.Item("mlkit", "离线翻译", "ML Kit")) + aiItems(prefs, Llm.targets(prefs))
                st.placeholder = "输入要翻译的文字"; st.resultHint = "译文会显示在这里"; st.busyText = "翻译中…"
                st.source = q; st.sourceIsSelection = fromSelection; st.sourceTag = ""
                if (q.isEmpty()) fromField = ""
                st.insertable = if (q.isEmpty() && !fromSelection) insertable().first else ""
                val fresh = resultFor == q
                st.buttons = if (result.isNotEmpty() && fresh) listOf("commit" to if (fromSelection || fromField.isNotEmpty()) "替换" else "上屏") else emptyList()
                if (!fresh) { st.result = ""; st.busy = q.isNotBlank() }
                if (q.isBlank()) st.status = ""
                if (!fresh && q.isNotBlank()) {
                    handler.removeCallbacks(run)
                    val cached = cache[key(q)]
                    if (cached != null) { resultFor = q; result = cached; st.result = cached; st.busy = false; st.status = ""; st.buttons = listOf("commit" to if (fromSelection || fromField.isNotEmpty()) "替换" else "上屏") }
                    else handler.postDelayed(run, delayFor(q))
                }
            }
            "rewrite" -> {
                val a = rewriteAction()
                st.modeLabel = if (custom) "改写 · 我来说" else "改写 · ${a.title}"
                st.showSwap = false
                st.menuCurrent = if (custom) "custom" else "act:${a.id}"
                aiHeader(st, aiTarget)
                st.sourceIsSelection = fromSelection && !custom
                if (custom) {
                    st.source = q; st.placeholder = "说说要怎么改，比如「语气软一点」"
                    st.sourceTag = if (fromField.isNotBlank()) "改 ${if (fromSelection) "选中" else "全文"} ${fromField.length} 字" else ""
                    st.insertable = ""
                } else {
                    st.source = q; st.placeholder = "要改的文字"; st.sourceTag = ""
                    st.insertable = if (q.isEmpty() && !fromSelection) insertable().first else ""
                }
                st.busyText = "${a.title.takeIf { !custom } ?: "改写"}中…"
                st.resultHint = when {
                    aiTarget == null -> "先在设置里添加 AI 服务"
                    custom -> "↵ 发出后，按这句话改"
                    else -> "按 ↵ 或「开始」，用「${a.title}」改；左上角换做法"
                }
                st.result = result; st.busy = rewriting; st.status = status
                st.buttons = when {
                    rewriting -> listOf("stop" to "停止")
                    result.isNotEmpty() -> listOf("copy" to "复制", "insert" to "插入", "apply" to if (fromSelection || fromField.isNotEmpty()) "替换" else "上屏")
                    aiTarget == null -> listOf("setup" to "去设置")
                    else -> listOf("go" to "开始")
                }
            }
            else -> {
                st.modeLabel = "聊天 · ${ai.kayName}"
                st.showSwap = false
                st.menuCurrent = ""
                aiHeader(st, aiTarget)
                st.source = q; st.sourceIsSelection = false; st.sourceTag = ""; st.insertable = ""
                st.placeholder = "跟${ai.kayName}说点什么"
                st.busyText = "${ai.kayName}在想…"
                st.resultHint = if (aiTarget == null) "先在设置里添加 AI 服务" else "回车发出"
                val busy = ai.chatBusy
                st.result = answer; st.busy = busy; st.status = status
                st.buttons = when {
                    busy -> listOf("stop" to "停止")
                    answer.isNotEmpty() -> listOf("all" to "看全部", "copy" to "复制", "insert" to "插入")
                    aiTarget == null -> listOf("setup" to "去设置")
                    else -> emptyList()
                }
            }
        }
        st.refresh()
    }

    private fun aiHeader(st: TranslateStrip, t: String?) {
        st.engineName = if (t == null) "未设置" else Llm.targetName(prefs, t)
        st.engines = aiItems(prefs, Llm.targets(prefs))
        st.engine = t.orEmpty()
    }

    /** ↵: what the strip is for. */
    fun enter() = when (mode) {
        "translate" -> commit()
        "rewrite" -> if (result.isNotEmpty() && !rewriting && !custom) button("apply") else rewrite()
        else -> send()
    }

    private fun button(b: String) {
        when (b) {
            "commit" -> commit()
            "go" -> rewrite()
            "apply" -> applyRewrite(replace = true)
            "insert" -> if (mode == "chat") { val t = answer; if (t.isNotEmpty()) { host.closeSink(); host.editor.type(t, pairable = false, source = TypingStats.Source.AI) } } else applyRewrite(replace = false)
            "copy" -> {
                val t = if (mode == "chat") answer else result
                (host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI", t))
                host.notice("已复制")
            }
            "stop" -> { ai.cancel(); rewriting = false; sync() }
            "all" -> ai.showConversation()
            "setup" -> host.openSettings("ai")
        }
    }

    // ---- 改写 ----

    private fun rewrite() {
        val q = host.query.toString()
        val src = if (custom) fromField else q
        if (src.isBlank()) { status = "没有要改的文字：在上面打字，或点「一键插入」"; sync(); return }
        if (custom && q.isBlank()) { status = "先说说要怎么改"; sync(); return }
        val a = if (custom) AiWriter.custom(q) else rewriteAction()
        rewriting = true; status = ""; result = ""; rewriteBase = if (a.diff) src else null
        sync()
        ai.rewrite(a, src,
            onText = { s -> if (mode == "rewrite") { result = s; strip.result = s; strip.refresh() } },
            onDone = { r ->
                rewriting = false
                r.onSuccess { result = it }.onFailure { result = ""; status = it.message ?: "出错了" }
                if (mode == "rewrite") sync()
            })
    }

    /** 替换 what it came from (the selection, or the field's text), or 插入 after it. */
    private fun applyRewrite(replace: Boolean) {
        val t = result
        if (t.isEmpty()) return
        val whole = fromField.isNotEmpty() && !fromSelection
        val sel = fromSelection
        host.closeSink()
        when {
            replace && whole -> host.editor.replace(t, wholeField = true)
            replace && sel -> host.editor.type(t, pairable = false, source = TypingStats.Source.AI)
            replace -> host.editor.type(t, pairable = false, source = TypingStats.Source.AI)
            else -> host.editor.insertAfterSelection(t)
        }
    }

    // ---- 聊天 ----

    private fun send() {
        val said = host.query.toString().trim()
        if (said.isEmpty() || ai.chatBusy) return
        host.query.setLength(0)
        status = ""; answer = ""
        ai.chat(said,
            onText = { s -> if (mode == "chat") { answer = s; strip.result = s; strip.busy = false; strip.refresh() } },
            onDone = { r ->
                r.onSuccess { answer = it }.onFailure { status = it.message ?: "没连上" }
                if (mode == "chat") sync()
            })
        sync(); host.syncBar()
    }

    // ---- 翻译 ----

    /**
     * How long to wait before asking. Offline translation costs nothing: at once, nearly. An AI
     * request is one of the day's calls: only once typing pauses, or a sentence is finished.
     */
    private fun delayFor(q: String): Long = when {
        fromSelection -> 0L
        service.engine == "mlkit" -> 400L
        q.trimEnd().lastOrNull()?.let { it in "。！？!?…\n" } == true -> 150L
        else -> prefs.translatePauseMs.toLong()
    }

    /** Translations already had, so going back to a text (a deleted character) asks nothing. */
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 40
    }
    private fun key(q: String) = "${service.engine}|$from|$to|$q"
    /** 上屏 pressed before the translation came: it is sent when it comes. */
    private var commitWhenReady = false

    private fun translate() {
        val q = host.query.toString()
        if (host.sink != Sink.TRANSLATE || mode != "translate" || q.isBlank()) return
        val k = key(q)
        service.translate(q, from, to, onPartial = { t ->
            if (host.query.toString() == q) { strip.result = t; strip.refresh() }
        }) { r ->
            if (host.sink != Sink.TRANSLATE || mode != "translate" || host.query.toString() != q) return@translate
            resultFor = q
            r.onSuccess { result = it; strip.status = ""; cache[k] = it }
             .onFailure { result = ""; strip.result = ""; strip.status = it.message ?: "翻译失败"; commitWhenReady = false }
            strip.busy = false
            sync()
            if (commitWhenReady && result.isNotEmpty()) { commitWhenReady = false; commit() }
        }
    }

    /**
     * What 一键插入 puts in the box: what was just copied (a message to answer, most often), or
     * else what is already written in the field.
     */
    private fun insertable(): Pair<String, String> {
        val cm = host.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(host.context)?.toString() }.getOrNull()?.trim().orEmpty()
        if (clip.isNotEmpty() && clip.length <= 2000) return clip to ""
        val raw = host.editor.textBefore(500)
        return raw.trim() to raw
    }
    /** What 一键插入 (or 改写) took from the field, to be replaced by the answer. */
    private var fromField = ""

    /** ↵ or 上屏: sends the translation when it is for the text as it is now. */
    fun commit() {
        val t = result
        if (t.isEmpty() || resultFor != host.query.toString()) {
            // still waiting for the pause: ask now, and send it when it comes
            if (host.query.isNotBlank() && host.sink == Sink.TRANSLATE) { commitWhenReady = true; handler.removeCallbacks(run); translate() }
            return
        }
        val field = fromField
        host.closeSink()
        // what was taken from the field gives way to its translation
        if (field.isNotEmpty() && host.editor.textBefore(field.length) == field) host.editor.replaceBefore(field.length, field.takeWhile { it.isWhitespace() })
        // a selection being translated is replaced: committing over it does that
        host.editor.type(t, pairable = false, source = TypingStats.Source.AI)
    }
}

/** Services' models as list rows: the short model name, and whose it is. */
internal fun aiItems(prefs: Prefs, targets: List<Pair<String, String>>) = targets.map { (t, _) ->
    PickList.Item(t, Llm.targetName(prefs, t), Llm.provider(Llm.targetId(t)).title)
}
