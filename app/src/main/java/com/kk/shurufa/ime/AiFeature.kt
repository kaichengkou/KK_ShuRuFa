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
 * AI 改写: the selection (or else the whole field) goes to the chosen model with one of the
 * actions or an instruction typed on the keyboard (输入指令, through the AI sink); the answer
 * streams into the panel and can replace the text, go in after it, or be copied.
 */
class AiFeature(private val host: Host) {
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
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onAction = { id -> AiWriter.ACTIONS.firstOrNull { it.id == id }?.let { start(it) } }
            onButton = { b ->
                when (b) {
                    "setup" -> host.openSettings("ai")
                    "replace" -> apply(replace = true)
                    "insert" -> apply(replace = false)
                    "retry" -> action?.let { start(it) }
                    "stop" -> stop()
                    "copy" -> {
                        (host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI", result))
                        host.notice("已复制")
                    }
                    "custom" -> host.openSink(Sink.AI)
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
        val src = source()
        panel.source = src
        panel.sourceLabel = if (fromSelection) "选中" else "全文"
        panel.running = null; panel.status = ""
        panel.setResult("", null)
        result = ""; action = null
        syncHeader()
    }

    /** 输入指令 finished: back on the panel, the instruction runs. */
    fun runInstruction(instruction: String) {
        host.show(Panel.AI)
        if (instruction.isNotBlank()) start(AiWriter.custom(instruction))
    }

    private fun start(a: AiWriter.Action) {
        val src = source()
        if (src.isBlank()) { panel.status = "输入框里还没有文字"; panel.setResult("", null); return }
        val id = ++run
        action = a
        panel.running = a.id; panel.doneTitle = a.title; panel.status = ""
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
    fun cancel() { run++ }

    /** 停止: the run is let go; what came so far stays, to use or retry. */
    private fun stop() {
        run++
        panel.running = null
        host.haptics.play(HapticEvent.OPTION)
        if (panel.hasResult()) { result = panel.resultText(); panel.status = "" }
        else { result = ""; panel.status = "已停止 · 点一个操作重新开始" }
        panel.invalidate()
    }
}
