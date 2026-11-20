package com.kk.shurufa.voice

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whisper models for 语音输入. They are large, so none ships in the APK: the chosen one is
 * downloaded once (from Hugging Face, or its mirror for networks where it is slow) and kept
 * in the app's files.
 */
object VoiceModels {
    class Model(val id: String, val title: String, val sizeMb: Int, val file: String, val note: String)

    /** 中文模型: SenseVoice-Small (int8), from the sherpa-onnx export; two files. */
    object SenseVoiceModel {
        const val TITLE = "SenseVoice（中文）"
        const val SIZE_MB = 240
        private const val REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
        val files = listOf("model.int8.onnx" to 230_000_000L, "tokens.txt" to 300_000L)
        fun dir(ctx: Context) = File(ctx.filesDir, "sensevoice").apply { mkdirs() }
        fun installed(ctx: Context) = files.all { (f, min) -> File(dir(ctx), f).let { it.isFile && it.length() >= min } }
        fun urls(source: String): List<Pair<String, String>> {
            val host = if (source == "mirror") "https://hf-mirror.com/" else "https://huggingface.co/"
            return files.map { (f, _) -> f to host + REPO + f }
        }
        fun delete(ctx: Context) { dir(ctx).deleteRecursively() }
    }

    /** Downloads [url] into [dest] with resume and progress (0..1 of this file). */
    fun fetch(url0: String, dest: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val part = File(dest.path + ".part")
        var url = URL(url0)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "KK-IME")
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code == 416) { conn.disconnect(); part.renameTo(dest); progress(1f); return }
            if (code !in 200..299) { conn.disconnect(); error("HTTP $code") }
            break
        }
        val resumed = conn.responseCode == 206
        val start = if (resumed) part.length() else 0L
        val total = start + conn.contentLengthLong.coerceAtLeast(0L)
        try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = start; var last = 0L
                    while (true) {
                        if (cancelled()) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0 && done - last > 1_000_000) { last = done; progress(done.toFloat() / total) }
                    }
                }
            }
        } finally { conn.disconnect() }
        if (total > 0 && part.length() < total) error("下载不完整")
        part.renameTo(dest)
        progress(1f)
    }

    /** Downloads the Chinese model (both files); progress is over the whole download. */
    fun downloadSenseVoice(ctx: Context, source: String, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val urls = SenseVoiceModel.urls(source)
        val weights = SenseVoiceModel.files.map { it.second.toFloat() }
        val sum = weights.sum()
        var before = 0f
        urls.forEachIndexed { i, (name, url) ->
            val dest = File(SenseVoiceModel.dir(ctx), name)
            if (!(dest.isFile && dest.length() >= SenseVoiceModel.files[i].second)) {
                fetch(url, dest, { p -> progress((before + p * weights[i]) / sum) }, cancelled)
                if (cancelled()) return
            }
            before += weights[i]
        }
        progress(1f)
    }

    val all = listOf(
        Model("base", "Base", 57, "ggml-base-q5_1.bin", "最快，适合短句；准确度一般"),
        Model("small", "Small（推荐）", 181, "ggml-small-q5_1.bin", "速度和准确度均衡，中文、英文、俄语都好用"),
        Model("turbo", "Large v3 Turbo", 547, "ggml-large-v3-turbo-q5_0.bin", "最准，识别要慢几秒；需要较新的手机"),
    )

    val sources = listOf(
        "hf" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/",
        "mirror" to "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/",
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "whisper").apply { mkdirs() }
    fun file(ctx: Context, m: Model) = File(dir(ctx), m.file)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[1]
    fun installed(ctx: Context, m: Model) = file(ctx, m).let { it.isFile && it.length() > m.sizeMb * 900_000L }

    /**
     * Downloads [m], reporting progress 0..1. Resumes a partial download. Blocking; run it
     * off the main thread. [cancelled] is polled between chunks.
     */
    fun download(ctx: Context, m: Model, source: String, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val base = sources.firstOrNull { it.first == source }?.second ?: sources[0].second
        val part = File(dir(ctx), m.file + ".part")
        var url = URL(base + m.file)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "KK-IME")
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code !in 200..299) { conn.disconnect(); error("HTTP $code") }
            break
        }
        val resumed = conn.responseCode == 206
        val start = if (resumed) part.length() else 0L
        val total = start + conn.contentLengthLong.coerceAtLeast(0L)
        try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = start
                    var last = 0L
                    while (true) {
                        if (cancelled()) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - last > 1_000_000) { last = done; progress(done.toFloat() / total) }
                    }
                }
            }
        } finally { conn.disconnect() }
        if (total > 0 && part.length() < total) error("下载不完整")
        part.renameTo(file(ctx, m))
        progress(1f)
    }
}
