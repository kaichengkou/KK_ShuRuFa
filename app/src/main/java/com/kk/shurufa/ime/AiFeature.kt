package com.kk.shurufa.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.AiPanel
import com.kk.shurufa.online.AiWriter
import com.kk.shurufa.online.Llm

/**
 * 小K's panel. 聊天: talking with 小K (回话 types through the AI sink; the answer streams in).
 * 改写 · 翻译: the selection (or else the whole field) goes
 * to the chosen model with one of the actions or an instruction typed on the keyboard (输入指令);
 * the answer streams into the panel and can replace the text, go in after it, or be copied.
 */
class AiFeature(private val host: Host, private val kay: Kay) {
    private val prefs get() = host.prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var panel: AiPanel
    private var result = ""
    private var fromSelection = false
    private var action: AiWriter.Action? = null
    /** Each run has a number; answers of older runs are dropped. */
    @Volatile private var run = 0

    fun build(): View {
        panel = AiPanel(host.context, prefs).apply {
            theme = host.kbTheme
            actions = AiWriter.ACTIONS.map { AiPanel.Act(it.id, it.title, it.group) }
            chatTab = CHAT
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onAction = { id -> AiWriter.ACTIONS.firstOrNull { it.id == id }?.let { start(it) } }
            onButton = { b ->
                when (b) {
                    "setup" -> host.openSettings("ai")
                    "replace" -> apply(replace = true)
                    "insert" -> apply(replace = false)
                    "clear" -> { run++; panel.running = null; com.kk.shurufa.keyboard.SharkLife.thinking = false; pendingSaid = ""; pendingAnswer = ""; kay.forgetTalk(); panel.status = ""; syncChat() }
                    "reply" -> { chatting = true; host.openSink(Sink.AI) }
                    "retry" -> action?.let { start(it) }
                    "stop" -> stop()
                    "copy" -> {
                        (host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI", result))
                        host.notice("已复制")
                    }
                    "custom" -> { chatting = false; host.openSink(Sink.AI) }
                    else -> if (b.startsWith("target:")) { prefs.put(Prefs.AI_PROVIDER, b.removePrefix("target:")); syncHeader() }
                }
            }
        }
        return panel
    }

    /** The service and model AI 改写 uses ("id|model"), or null when none is set up. */
    private fun target(): String? = Llm.resolve(prefs, prefs.aiProvider)

    private fun syncHeader() {
        val t = target()
        panel.needsSetup = t == null
        panel.targets = aiItems(prefs, Llm.targets(prefs))
        panel.currentTarget = t.orEmpty()
        panel.provider = if (t == null) "" else Llm.targetName(prefs, t)
        panel.invalidate()
    }

    /** The text the AI works on: the selection, or else everything in the field. */
    private fun source(): String {
        val ed = host.editor
        ed.selectedText()?.takeIf { it.isNotBlank() }?.let { fromSelection = true; return it }
        fromSelection = false
        return ed.allText() ?: (ed.textBefore(4000) + ed.textAfter(4000))
    }

    fun onOpened() {
        host.finishComposing()
        panel.kayName = kay.name
        val src = source()
        panel.source = src
        panel.sourceLabel = if (fromSelection) "选中" else "全文"
        panel.running = null; panel.status = ""
        panel.setResult("", null)
        result = ""; action = null
        syncHeader()
        // talking goes on where it was; otherwise with text in the field it is about the text
        if (!chatting) panel.selectGroup(if (src.isBlank() || kay.talk.isNotEmpty()) CHAT else "改写")
        syncChat()
    }

    // ---- 聊天 ----

    /** The AI sink is 回话 (talking), not 输入指令. */
    var chatting = false
    private var pendingSaid = ""
    private var pendingAnswer = ""

    /** 接着聊 from the line of the day: it starts the conversation, and the keyboard is ready to answer. */
    fun openChat(first: String?) {
        if (first != null && kay.talk.isEmpty()) kay.talk += Kay.Turn(false, first)
        chatting = true
        host.show(Panel.AI)
        panel.selectGroup(CHAT)
        syncChat()
        if (target() != null) host.openSink(Sink.AI)
    }

    /** What was said, styled: 主人's words in a softer ink, 小K's name in the accent. */
    private fun syncChat() {
        val sb = android.text.SpannableStringBuilder()
        fun line(who: String, text: String, mine: Boolean) {
            if (sb.isNotEmpty()) sb.append("\n")
            val start = sb.length
            sb.append(who).append("  ")
            sb.setSpan(android.text.style.ForegroundColorSpan(if (mine) host.kbTheme.hint else host.kbTheme.accent), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.82f), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val t0 = sb.length
            sb.append(text)
            if (mine) sb.setSpan(android.text.style.ForegroundColorSpan(host.kbTheme.inkSoft), t0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (t in kay.talk) line(if (t.mine) "我" else kay.name, t.text, t.mine)
        if (pendingSaid.isNotEmpty()) {
            line("我", pendingSaid, true)
            if (pendingAnswer.isNotEmpty()) line(kay.name, pendingAnswer, false)
        }
        panel.chat = sb
        panel.chatHasAnswer = kay.talk.any { !it.mine }
    }

    private fun say(said: String) {
        host.show(Panel.AI)
        panel.selectGroup(CHAT)
        val t = target()
        if (t == null) { panel.status = "先在设置里填好 AI 服务的 Key"; return }
        if (!kay.mayTalk()) {
            kay.talk += Kay.Turn(true, said); kay.talk += Kay.Turn(false, "今天聊得够多啦，明天见 (｡･ω･｡)ﾉ")
            syncChat(); return
        }
        val id = ++run
        pendingSaid = said; pendingAnswer = ""
        panel.running = CHAT; panel.status = ""
        com.kk.shurufa.keyboard.SharkLife.thinking = true
        syncChat()
        kay.talked()
        Thread {
            com.kk.shurufa.online.AiBudget.asked(prefs)
            val r = runCatching {
                Llm.completeTarget(prefs, t, kay.chatSystem(), kay.chatUser(said),
                    onText = { s -> handler.post { if (id == run) { pendingAnswer = s.trim(); syncChat() } } },
                    cancelled = { id != run })
            }
            handler.post {
                if (id != run) return@post
                panel.running = null
                com.kk.shurufa.keyboard.SharkLife.thinking = false
                pendingSaid = ""; pendingAnswer = ""
                r.onSuccess { kay.heard(said, it) }
                 .onFailure { kay.talk += Kay.Turn(true, said); panel.status = it.message ?: "没连上" }
                syncChat()
                host.haptics.play(HapticEvent.GESTURE)
            }
        }.apply { isDaemon = true; name = "kk-kay" }.start()
    }


    /** 输入指令 or 回话 finished: back on the panel, the instruction runs or 小K hears it. */
    fun runInstruction(instruction: String) {
        if (chatting) { if (instruction.isNotBlank()) say(instruction) else host.show(Panel.AI); return }
        host.show(Panel.AI)
        if (instruction.isNotBlank()) start(AiWriter.custom(instruction))
    }

    private fun start(a: AiWriter.Action) {
        val src = source()
        if (src.isBlank()) { panel.status = "输入框里还没有文字"; panel.setResult("", null); return }
        val id = ++run
        action = a
        panel.running = a.id; panel.doneTitle = a.title; panel.status = ""
        com.kk.shurufa.keyboard.SharkLife.thinking = true
        panel.setResult("", null)
        val t = target() ?: run { panel.running = null; panel.status = "先在设置里添加 AI 服务"; return }
        Thread {
            val r = runCatching {
                Llm.completeTarget(prefs, t, AiWriter.SYSTEM, AiWriter.prompt(a, src),
                    onText = { s -> handler.post { if (id == run) panel.setResult(s, null) } }, cancelled = { id != run })
            }
            handler.post {
                if (id != run) return@post
                panel.running = null
                com.kk.shurufa.keyboard.SharkLife.thinking = false
                r.onSuccess { result = it; panel.setResult(it, if (a.diff) src else null) }
                 .onFailure { result = ""; panel.setResult("", null); panel.status = it.message ?: "出错了" }
                host.haptics.play(HapticEvent.GESTURE)
            }
        }.apply { isDaemon = true; name = "kk-ai" }.start()
    }

    /** 替换 (the selection, or the whole field — undoable) or 插入 (after the text). */
    private fun apply(replace: Boolean) {
        val t = result
        if (t.isEmpty()) return
        run++
        when {
            replace && !fromSelection -> host.editor.replace(t, wholeField = true)
            replace -> host.editor.type(t, pairable = false, source = TypingStats.Source.AI)
            else -> host.editor.insertAfterSelection(t)
        }
        host.show(Panel.LETTERS)
    }

    /** Leaving the panel stops a run that is still streaming. */
    fun cancel() {
        run++
        com.kk.shurufa.keyboard.SharkLife.thinking = false
        if (pendingSaid.isNotEmpty()) { kay.talk += Kay.Turn(true, pendingSaid); pendingSaid = ""; pendingAnswer = "" }
        if (::panel.isInitialized) panel.running = null
    }

    private companion object { const val CHAT = "聊天" }

    /** 停止: the run is let go; what came so far stays, to use or retry. */
    private fun stop() {
        run++
        com.kk.shurufa.keyboard.SharkLife.thinking = false
        panel.running = null
        host.haptics.play(HapticEvent.OPTION)
        if (panel.hasResult()) { result = panel.resultText(); panel.status = "" }
        else { result = ""; panel.status = "已停止 · 点一个操作重新开始" }
        panel.invalidate()
    }
}
