package com.kk.shurufa.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.kk.shurufa.Prefs
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import java.io.File
import kotlin.math.sqrt

/**
 * 语音输入. Two engines:
 *  - "whisper": records 16 kHz audio while the button is on, then transcribes it on the phone
 *    with whisper.cpp (offline, accurate, punctuated);
 *  - "system": Android's own recognizer, which streams partial results.
 * Callbacks arrive on the main thread.
 */
class VoiceEngine(private val context: Context, private val prefs: Prefs) {
    interface Listener {
        fun onLevel(level: Float) {}
        fun onState(state: State, detail: String = "") {}
        fun onPartial(text: String) {}
        fun onResult(text: String) {}
    }

    enum class State { IDLE, LISTENING, RECOGNIZING, ERROR }

    var listener: Listener? = null
    var state = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("kk-voice").apply { start() }
    private val work = Handler(worker.looper)
    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private val samples = FloatArrayBuilder()
    private var ctx = 0L
    private var ctxModel = ""
    private var system: SpeechRecognizer? = null

    private fun post(s: State, detail: String = "") { main.post { state = s; listener?.onState(s, detail) } }

    fun modelReady(): Boolean = VoiceModels.installed(context, VoiceModels.byId(prefs.voiceModel))

    /** [lang] "zh" "en" "ru" or "auto". */
    fun start(lang: String) {
        if (state == State.LISTENING || state == State.RECOGNIZING) return
        if (prefs.voiceEngine == "system") startSystem(lang) else startWhisper(lang)
    }

    /** Stops listening and recognizes what was said. */
    fun stop() {
        if (prefs.voiceEngine == "system") system?.stopListening() else recording = false
    }

    /** Stops without a result. */
    fun cancel() {
        recording = false
        samples.clear()
        system?.cancel()
        runCatching { WhisperBridge.cancel() }
        post(State.IDLE)
    }

    fun release() {
        cancel()
        work.post { if (ctx != 0L) { WhisperBridge.free(ctx); ctx = 0L } }
        worker.quitSafely()
        system?.destroy(); system = null
    }

    // ---- whisper ----

    @SuppressLint("MissingPermission")
    private fun startWhisper(lang: String) {
        val model = VoiceModels.byId(prefs.voiceModel)
        if (!VoiceModels.installed(context, model)) { post(State.ERROR, "还没有下载语音模型"); return }
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate))
        } catch (e: Exception) { post(State.ERROR, "无法打开麦克风"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); post(State.ERROR, "无法打开麦克风"); return }
        record = rec
        samples.clear()
        recording = true
        post(State.LISTENING)
        // load the model while the user speaks
        work.post { loadModel(model) }
        Thread {
            val buf = FloatArray(1600)
            rec.startRecording()
            val maxSamples = rate * prefs.voiceMaxSec
            while (recording && samples.size < maxSamples) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                samples.add(buf, n)
                var sum = 0f
                for (i in 0 until n) sum += buf[i] * buf[i]
                val rms = sqrt(sum / n)
                main.post { listener?.onLevel((rms * 12f).coerceIn(0f, 1f)) }
            }
            rec.stop(); rec.release(); record = null
            val pcm = samples.toArray()
            samples.clear()
            if (state != State.LISTENING) return@Thread
            if (pcm.size < rate / 3) { post(State.IDLE); return@Thread }
            post(State.RECOGNIZING)
            work.post { recognize(model, pcm, lang) }
        }.apply { name = "kk-mic" }.start()
    }

    private fun loadModel(m: VoiceModels.Model) {
        if (ctx != 0L && ctxModel == m.id) return
        WhisperBridge.ensureLoaded()
        if (ctx != 0L) { WhisperBridge.free(ctx); ctx = 0L }
        ctx = WhisperBridge.init(VoiceModels.file(context, m).absolutePath)
        ctxModel = m.id
    }

    private fun recognize(m: VoiceModels.Model, pcm: FloatArray, lang: String) {
        try {
            loadModel(m)
            if (ctx == 0L) { post(State.ERROR, "模型加载失败，请在设置里重新下载"); return }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            // a prompt in the expected style keeps Chinese simplified and punctuated
            val prompt = when (lang) {
                "zh" -> "以下是普通话的句子，使用简体中文和标点符号。"
                "ru" -> "Это предложение на русском языке."
                "en" -> "This is a sentence in English."
                else -> ""
            }
            var text = WhisperBridge.transcribe(ctx, pcm, lang, threads, prompt).trim()
            val detected = if (lang == "auto") WhisperBridge.lastLanguage(ctx) else lang
            if (detected == "zh") text = simplified(text)
            if (text.isEmpty()) { post(State.IDLE, "没有听清"); return }
            main.post { state = State.IDLE; listener?.onResult(text); listener?.onState(State.IDLE) }
        } catch (e: Throwable) {
            post(State.ERROR, "识别失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun simplified(t: String): String {
        if (!RimeEngine.ready) return t
        val cfg = File(RimeEngine.sharedDir(context), "opencc/t2s.json")
        return if (cfg.isFile) runCatching { RimeBridge.t2s(t, cfg.absolutePath) }.getOrDefault(t) else t
    }

    // ---- system recognizer ----

    private fun startSystem(lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { post(State.ERROR, "这台手机没有系统语音识别服务"); return }
        val r = system ?: SpeechRecognizer.createSpeechRecognizer(context).also { system = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { post(State.LISTENING) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { post(State.RECOGNIZING) }
            override fun onError(error: Int) {
                post(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) State.IDLE else State.ERROR,
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络不可用"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
                        else -> "识别出错（$error）"
                    })
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                state = State.IDLE
                if (t.isNotEmpty()) listener?.onResult(t)
                listener?.onState(State.IDLE)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { listener?.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val tag = when (lang) { "zh" -> "zh-CN"; "en" -> "en-US"; "ru" -> "ru-RU"; else -> null }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        }
        post(State.LISTENING)
        r.startListening(intent)
    }
}

/** A growable float buffer for recorded audio. */
class FloatArrayBuilder {
    private var data = FloatArray(16000 * 8)
    var size = 0
        private set
    @Synchronized fun add(src: FloatArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n); size += n
    }
    @Synchronized fun toArray(): FloatArray = data.copyOf(size)
    @Synchronized fun clear() { size = 0 }
}
