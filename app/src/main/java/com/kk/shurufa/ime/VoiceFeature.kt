package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.PickList
import com.kk.shurufa.keyboard.VoicePanel
import com.kk.shurufa.online.Llm
import com.kk.shurufa.online.TranslateService
import com.kk.shurufa.voice.PermissionActivity
import com.kk.shurufa.voice.VoiceEngine
import com.kk.shurufa.voice.VoiceText

/**
 * 语音输入: opening the panel starts listening (when the microphone and a model are ready); each
 * sentence grows in the field as it is spoken and is committed when the speaker pauses, or is
 * first translated (译成…). The recognizer's models are large, so they are let go a minute after
 * the panel was left, and at once when the system runs short of memory.
 */
class VoiceFeature(private val host: Host, private val translate: TranslateService) {
    private val prefs get() = host.prefs
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: VoiceEngine? = null
    private lateinit var view: VoicePanel
    /** Recognition language picked on the panel for this session; null follows the setting. */
    private var langPick: String? = null
    /** 转文字 or 翻译 (each sentence translated before it goes in). */
    private var translating = false
    private var spoke = false
    private var lastInsert = ""
    private val release = Runnable { recognizer?.releaseModels() }

    fun build(): View {
        view = VoicePanel(host.context, prefs).apply {
            theme = host.kbTheme
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onMic = { onMic() }
            onCancel = { recognizer?.cancel(); host.editor.finishComposingText() }
            onLanguage = {
                val order = listOf("zh", "en", "ru", "auto")
                langPick = order[(order.indexOf(lang()) + 1) % order.size]
                syncChips()
                // switching between the Chinese and the foreign model needs a restart
                if (recognizer?.state == VoiceEngine.State.LISTENING) { recognizer?.cancel(); onMic() }
            }
            onMode = { translating = !translating; syncChips() }
            onUndo = { undo() }
            onEngine = { e ->
                prefs.put(Prefs.VOICE_ENGINE, e)
                recognizer?.cancel()
                syncChips()
                onOpened()
            }
            onDelete = { repeat -> if (host.editor.deleteBackward() || !repeat) host.haptics.play(if (repeat) HapticEvent.REPEAT else HapticEvent.BACKSPACE) }
        }
        val panel = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return panel
    }

    private fun lang(): String = langPick ?: prefs.voiceLang.let {
        if (it == "follow") (if (host.isZh) "zh" else host.language.takeIf { l -> l == "ru" || l == "en" } ?: "zh") else it
    }

    private fun langName(l: String) = when (l) { "zh" -> "中文"; "en" -> "English"; "ru" -> "Русский"; else -> "自动" }

    private fun syncChips() {
        val engines = listOf(
            PickList.Item("whisper", "本地模型", "SenseVoice · Whisper"),
            PickList.Item("system", "系统识别", "Android"),
        ) + aiItems(prefs, Llm.speechTargets(prefs))
        val cur = prefs.voiceEngine.let { if (it.contains('|')) Llm.resolve(prefs, it) ?: "whisper" else it }
        view.engines = engines
        view.engine = cur
        view.engineName = when {
            cur == "system" -> "系统识别"
            cur.contains('|') -> Llm.provider(Llm.targetId(cur)).title
            else -> if (engine().usesWhisper(lang())) "Whisper" else "SenseVoice"
        }
        view.language = langName(lang())
        view.translating = translating
        view.mode = if (translating) "译成" + TranslateService.name(prefs.translateTarget) else "转文字"
    }

    private fun engine(): VoiceEngine = recognizer ?: VoiceEngine(host.context, prefs).also { e ->
        recognizer = e
        e.listener = object : VoiceEngine.Listener {
            override fun onLevel(level: Float) = view.setLevel(level)
            override fun onPartial(text: String) {
                view.partial = text
                // 流式: the sentence grows in the field as it is spoken
                if (!translating) host.editor.compose(preview(text))
            }
            override fun onSegment(text: String, pauseMs: Long) = onSentence(text)
            override fun onState(state: VoiceEngine.State, detail: String) = onEngineState(state, detail)
        }
    }

    private fun onEngineState(state: VoiceEngine.State, detail: String) {
        view.look = when (state) {
            VoiceEngine.State.LISTENING -> VoicePanel.Look.LISTENING
            VoiceEngine.State.LOADING -> VoicePanel.Look.LOADING
            VoiceEngine.State.RECOGNIZING -> VoicePanel.Look.WORKING
            else -> VoicePanel.Look.READY
        }
        view.status = when (state) {
            VoiceEngine.State.LISTENING -> "正在听 · 说完点一下立即识别"
            VoiceEngine.State.LOADING -> "正在加载模型…"
            VoiceEngine.State.RECOGNIZING -> "识别中…"
            else -> detail.ifEmpty { if (spoke) "点按继续说" else "点按开始说话" }
        }
        if (state == VoiceEngine.State.IDLE || state == VoiceEngine.State.ERROR) {
            view.partial = ""
            host.editor.finishComposingText()
            // 说完回到键盘 (when something was said and the setting is on)
            if (state == VoiceEngine.State.IDLE && prefs.voiceReturn && spoke && host.panel == Panel.VOICE)
                handler.postDelayed({ if (host.panel == Panel.VOICE && recognizer?.state == VoiceEngine.State.IDLE) host.show(Panel.LETTERS) }, 350)
            // nobody is listening any more: the models can go after a while
            if (host.panel != Panel.VOICE) scheduleRelease()
        }
        if (state == VoiceEngine.State.LISTENING) host.haptics.play(HapticEvent.GESTURE)
    }

    /** Opening the panel starts listening right away, when it can. */
    fun onOpened() {
        handler.removeCallbacks(release)
        syncChips()
        view.partial = ""
        view.canUndo = false
        spoke = false
        val l = lang()
        when {
            !PermissionActivity.granted(host.context) -> {
                view.look = VoicePanel.Look.READY
                view.status = "需要麦克风权限，点麦克风去允许"
            }
            !engine().modelReady(l) -> {
                view.look = VoicePanel.Look.NEED_MODEL
                view.status = if (engine().usesWhisper(l)) "先下载外文模型，点麦克风去设置" else "先下载中文模型，点麦克风去设置"
            }
            else -> { host.finishComposing(); engine().start(l) }
        }
    }

    /** The panel was left: stop listening, and let the models go a minute later. */
    fun onClosed() {
        recognizer?.cancel()
        scheduleRelease()
    }

    private fun scheduleRelease() {
        handler.removeCallbacks(release)
        if (recognizer != null) handler.postDelayed(release, RELEASE_AFTER_MS)
    }

    /** The system is short of memory: free the models now unless they are in use. */
    fun trimMemory() {
        val e = recognizer ?: return
        if (e.state == VoiceEngine.State.IDLE || e.state == VoiceEngine.State.ERROR) { handler.removeCallbacks(release); e.releaseModels() }
    }

    fun destroy() {
        handler.removeCallbacks(release)
        recognizer?.release(); recognizer = null
    }

    private fun onMic() {
        val e = engine()
        val l = lang()
        when {
            !PermissionActivity.granted(host.context) -> PermissionActivity.request(host.context)
            !e.modelReady(l) -> host.openSettings("voice")
            e.state == VoiceEngine.State.LISTENING -> e.stop()
            e.state == VoiceEngine.State.LOADING || e.state == VoiceEngine.State.RECOGNIZING -> {}
            else -> { host.finishComposing(); e.start(l) }
        }
    }

    /** What a growing sentence would look like once committed (shown as composing text). */
    private fun preview(text: String): String =
        VoiceText.format(text, host.editor.textBefore(4), spoke, prefs.voicePunct, prefs.voicePangu, prefs.voiceFillers).insert

    private fun onSentence(text: String) {
        if (text.isBlank()) { host.editor.finishComposingText(); return }
        if (translating) {
            view.partial = text
            translate.translate(text, lang(), prefs.translateTarget) { r ->
                r.onSuccess { insert(it) }.onFailure { view.status = it.message ?: "翻译失败" }
            }
            return
        }
        insert(text)
    }

    private fun insert(text: String) {
        val edit = VoiceText.format(text, host.editor.textBefore(4), spoke, prefs.voicePunct, prefs.voicePangu, prefs.voiceFillers)
        // the composing preview is replaced by the final text
        host.editor.commitVoice(edit.deleteBefore, edit.insert)
        lastInsert = edit.insert
        spoke = true
        view.canUndo = true
        view.partial = ""
    }

    /** 撤回上一句: takes the last sentence back out of the field. */
    private fun undo() {
        val t = lastInsert
        if (t.isEmpty()) return
        host.editor.deleteIfBefore(t)
        lastInsert = ""
        view.canUndo = false
        host.haptics.play(HapticEvent.GESTURE)
    }

    private companion object {
        /** How long the recognizer's models stay loaded after the panel was left. */
        const val RELEASE_AFTER_MS = 60_000L
    }
}
