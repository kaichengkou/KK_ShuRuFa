package com.kk.shurufa.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kk.shurufa.Prefs

// 设置 · 语音输入.

/** A downloadable model row: title, size, state, and 下载 / 停止 / 删除. */
@Composable
private fun ModelRow(title: String, note: String, installed: Boolean, selected: Boolean?, onSelect: (() -> Unit)?,
                     download: ((progress: (Float) -> Unit, cancelled: () -> Boolean) -> Unit), delete: () -> Unit) {
    var progress by remember { mutableStateOf(-1f) }
    var have by remember { mutableStateOf(installed) }
    var cancel by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    Row(Modifier.fillMaxWidth().let { if (onSelect != null) it.clickable { onSelect() } else it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) RadioButton(selected = selected, onClick = { onSelect?.invoke() })
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(when {
                progress >= 0f -> "正在下载 ${(progress * 100).toInt()}%"
                msg.isNotEmpty() -> msg
                have -> "已下载 · $note"
                else -> note
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            progress >= 0f -> TextButton(onClick = { cancel = true }) { Text("停止") }
            have -> TextButton(onClick = { delete(); have = false; msg = "已删除" }) { Text("删除") }
            else -> TextButton(onClick = {
                progress = 0f; cancel = false; msg = ""
                Thread {
                    val r = runCatching { download({ p -> main.post { progress = p } }, { cancel }) }
                    main.post {
                        progress = -1f
                        r.onSuccess { if (!cancel) { have = true; msg = "" } else msg = "已停止，下次会接着下载" }
                         .onFailure { msg = "下载失败：${it.message ?: "网络错误"}，可以换个下载来源再试" }
                    }
                }.start()
            }) { Text("下载") }
        }
    }
    if (progress >= 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun VoicePage(prefs: Prefs) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val granted = remember(tick) { com.kk.shurufa.voice.PermissionActivity.granted(ctx) }
    Group("识别方式") {
        run {
            var pick by remember { mutableStateOf(false) }
            var cur by remember { mutableStateOf(prefs.voiceEngine) }
            val engines = listOf("whisper" to "本地模型（离线）", "system" to "系统语音识别") + com.kk.shurufa.online.Llm.speechTargets(prefs)
            PickRow("识别用", engines.firstOrNull { it.first == cur }?.second ?: com.kk.shurufa.online.Llm.targetLabel(prefs, cur)) { pick = true }
            if (pick) PickerDialog("识别用", engines, cur, onPick = { cur = it; prefs.put(Prefs.VOICE_ENGINE, it) }, onClose = { pick = false })
            Note("也可以用 AI 服务识别：OpenAI 格式的服务（OpenAI、硅基流动、Groq 等）需要先在 联网功能 里启用一个语音模型，如 whisper-1、gpt-4o-mini-transcribe、FunAudioLLM/SenseVoiceSmall、whisper-large-v3-turbo；Gemini 直接用它的对话模型听写。AI 识别要联网，每段说完才出字。语音面板上也能随时切换。")
        }
        Note("本地模型在手机上离线识别，边说边出字。中文和英文都用 SenseVoice（专为中文训练，英文也很好，带标点）；俄语它不支持，用 Whisper。系统识别用手机自带的服务，准确度取决于手机。")
        Choice(prefs, Prefs.VOICE_EN_MODEL, "英文用", listOf("sensevoice" to "SenseVoice（流式）", "whisper" to "Whisper"))
        Toggle(prefs, Prefs.VOICE_RETURN, "说完自动回到键盘", "自动结束收音后回到字母键盘", false)
        Choice(prefs, Prefs.VOICE_LANG, "识别语言", listOf("follow" to "跟随键盘", "zh" to "中文", "en" to "英文", "ru" to "俄语"))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(if (granted) "已允许使用麦克风" else "还没有麦克风权限", Modifier.weight(1f))
            if (!granted) Button(onClick = { com.kk.shurufa.voice.PermissionActivity.request(ctx) }) { Text("允许") }
        }
    }
    Group("断句和标点") {
        Choice(prefs, Prefs.VOICE_PUNCT, "标点", listOf("full" to "完整标点", "chat" to "聊天风格", "none" to "不加标点"))
        Note("边想边说时中途的停顿不会把话截断：一段话会整体识别，逗号和句号由模型按意思决定。完整标点：保留模型的标点。聊天风格：每段末尾不加句号，下一段用逗号接上。不加标点：只用空格分开。")
        Toggle(prefs, Prefs.VOICE_FILLERS, "去掉语气词", "嗯、呃、额这类停顿时的声音不上屏", true)
        Toggle(prefs, Prefs.VOICE_PANGU, "中英文之间加空格", "“我用 iPhone 拍了 3 张”", false)
        IntSlider(prefs, Prefs.VOICE_COMMIT_PAUSE, "一段话说完的停顿", 2000, 1000..4000, "ms", "停顿超过这么久，这段话才算说完并定稿；想得慢可以调长")
        IntSlider(prefs, Prefs.VOICE_AUTO_STOP, "自动结束", 3, 0..10, "秒", "这么久没有说话就停止收音；0 为不自动结束")
    }
    Group("模型") {
        Choice(prefs, Prefs.VOICE_SOURCE, "下载来源", listOf("hf" to "Hugging Face", "mirror" to "国内镜像"))
        Text("中文", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ModelRow("${com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.TITLE} · ${com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.SIZE_MB} MB",
            "阿里通义 SenseVoice-Small，中文准确率高，识别很快，自带标点和数字格式",
            remember(tick) { com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.installed(ctx) }, null, null,
            { p, c -> com.kk.shurufa.voice.VoiceModels.downloadSenseVoice(ctx, prefs.voiceSource, p, c) },
            { com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.delete(ctx) })
        Text("英文、俄语（Whisper，选一个）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        var selected by remember { mutableStateOf(prefs.voiceModel) }
        for (m in com.kk.shurufa.voice.VoiceModels.all) {
            ModelRow("${m.title} · ${m.sizeMb} MB", m.note, remember(tick) { com.kk.shurufa.voice.VoiceModels.installed(ctx, m) },
                selected == m.id, { selected = m.id; prefs.put(Prefs.VOICE_MODEL, m.id) },
                { p, c -> com.kk.shurufa.voice.VoiceModels.download(ctx, m, prefs.voiceSource, p, c) },
                { com.kk.shurufa.voice.VoiceModels.file(ctx, m).delete() })
        }
        Note("模型只存在手机里，录音不会上传。下载建议连 Wi-Fi。")
    }
}
