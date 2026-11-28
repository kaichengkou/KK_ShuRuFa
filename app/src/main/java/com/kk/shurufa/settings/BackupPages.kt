package com.kk.shurufa.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kk.shurufa.BuildConfig
import com.kk.shurufa.R
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.LayoutRepo

// 设置 · 关于与更新, 备份与恢复 and 布局文件.

/** 关于与更新: the version, 检查更新, what the keyboard sends where, and whose work it is built on. */
@Composable
internal fun AboutPage() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf("") }
    var rel by remember { mutableStateOf<com.kk.shurufa.online.Updates.Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun check() {
        busy = true; state = "正在检查…"
        Thread {
            val r = com.kk.shurufa.online.Updates.latest()
            main.post {
                busy = false
                r.fold({ x -> rel = x; state = if (x.newer) "有新版本 ${x.version}" else "已经是最新版本（${x.version}）" }, { state = it.message ?: "检查失败" })
            }
        }.start()
    }
    LaunchedEffect(Unit) { check() }
    Group("版本") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(64.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("KK输入法", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.isNotEmpty()) Note(state)
        rel?.takeIf { it.newer }?.let { x ->
            if (x.notes.isNotEmpty()) Note(x.notes.take(600))
            FilledTonalButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(x.apk ?: x.page)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(if (x.apk != null) "下载 ${x.version}" else "去下载页") }
        }
        TextButton(enabled = !busy, onClick = { check() }) { Text("检查更新") }
        Note("检查更新只会访问 GitHub 上这个项目的发布页，不带任何你的信息。打开设置时每天最多自动查一次。")
    }
    Group("隐私") {
        Note("打字、联想、纠错、学习、语音识别都在手机本地完成，学到的东西只存在本机。只有你自己打开并填了 Key 的 AI 功能（改写、翻译、聊天、云联想、AI 候选、AI 写一言）会把相关文字发给你选的服务；密码框、地址栏和临时模式里什么都不发。")
    }
    Group("开源许可") {
        Note("中文引擎 librime、librime-lua（BSD-3-Clause）与 librime-octagram（GPL-3.0）；雾凇拼音 rime-ice（GPL-3.0）；OpenCC（Apache-2.0）；pinyin-data（MIT）；chinese-poetry（MIT）；FrequencyWords（CC-BY-SA-4.0）与 Tatoeba（CC-BY 2.0 FR）；SenseVoice、whisper.cpp、Silero VAD、ONNX Runtime；Unicode Emoji；ML Kit 翻译。")
    }
}

/** 备份与恢复: everything the person made the keyboard into, in one file. */
@Composable
internal fun BackupPage() {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf<com.kk.shurufa.Backup.Summary?>(null) }
    val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "正在备份…"
        Thread {
            val r = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { com.kk.shurufa.Backup.export(ctx, it) } ?: error("写不进这个位置") }
            main.post {
                busy = false
                status = r.fold({ s -> "已备份：${s.settings} 项设置、${s.words} 条学到的词、${s.phrases} 条自定义词、${s.dicts} 个导入词库" }, { "备份失败：${it.message}" })
            }
        }.start()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "正在恢复…"
        Thread {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)?.use { com.kk.shurufa.Backup.restore(ctx, it) } ?: error("读不到文件") }
            main.post {
                busy = false
                r.fold({ s -> restored = s; status = "" }, { status = "恢复失败：${it.message}" })
            }
        }.start()
    }
    Group("备份") {
        Note("存成一个 .zip 文件：所有设置、最近用过的符号和表情、点按落点、雾凇学到的词和顺序、自定义词库、导入的词库、俄语学到的词、打字数据和剪贴板收藏。不含 API Key（换手机后要重新填）、剪贴板历史和下载的模型。")
        FilledTonalButton(enabled = !busy, onClick = { save.launch("KK输入法备份-$stamp.zip") }) { Text("备份到文件…") }
    }
    val saveSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val r = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { com.kk.shurufa.Backup.exportSettings(ctx, it) } ?: error("写不进这个位置") }
        status = r.fold({ n -> "已导出 $n 项设置" }, { "导出失败：${it.message}" })
    }
    Group("导出设置") {
        Note("只导出设置本身，存成一个 .json 文件：不含学到的词、剪贴板、打字数据，也不含任何 AI 服务的 Key、地址和模型。可以拿来做别的手机上的初始设置。")
        FilledTonalButton(enabled = !busy, onClick = { saveSettings.launch("KK输入法设置-$stamp.json") }) { Text("导出设置…") }
    }
    Group("恢复") {
        Note("从备份文件恢复。设置会被备份里的覆盖；学到的词、自定义词和收藏是合并进来的，这台手机上已经学到的不会丢。恢复后输入法会重新启动并重新部署词库（约一分钟）。")
        FilledTonalButton(enabled = !busy, onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("从文件恢复…") }
    }
    if (status.isNotEmpty()) Note(status)
    restored?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("已恢复") },
            text = { Text("${s.settings} 项设置、${s.words} 条学到的词、${s.phrases} 条新的自定义词、${s.dicts} 个导入词库。\n\n输入法现在重新启动，下次打开键盘时会重新部署词库，约一分钟。") },
            confirmButton = {
                TextButton(onClick = {
                    // a clean start: every part of the keyboard reads the restored files again
                    val again = android.content.Intent(ctx, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ctx.startActivity(again)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("好") }
            },
        )
    }
}

@Composable
internal fun FilesPage() {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<String?>(null) }
    Group {
        Note("所有键盘布局都是 YAML 文件。导出后可以用文本编辑器改键位、滑动字符、长按选项、符号分类和数学键盘；改完点“重新加载”。删掉某个文件就恢复默认。只想改几个键的话，用“键位编辑”更方便。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilledTonalButton(onClick = { path = LayoutRepo(ctx).exportDefaults().absolutePath }) { Text("导出布局文件") }
            FilledTonalButton(onClick = { Prefs(ctx).put(Prefs.LAYOUT_RELOAD, System.currentTimeMillis().toString()) }) { Text("重新加载") }
        }
        path?.let { Note("已导出到：$it") }
    }
}
