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
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.kk.shurufa.Prefs
import com.kk.shurufa.rime.RimeBridge
import com.kk.shurufa.rime.RimeEngine
import java.io.File
import kotlin.math.sqrt

/**
 * 语音输入, streaming. The microphone runs continuously; Silero VAD finds where each sentence
 * starts and ends. While a sentence is being spoken it is recognized again every half second
 * ([Listener.onPartial], shown in the field as it grows); when the speaker pauses it is
 * recognized once more and committed ([Listener.onSegment]). After a few seconds without
 * speech the engine stops by itself.
 *
 * Chinese (and mixed Chinese-English) goes to SenseVoice; English and Russian to Whisper,
 * which is slower, so it only shows the final text of each sentence. The "system" engine uses
 * Android's recognizer instead. Callbacks arrive on the main thread.
 */
class VoiceEngine(private val context: Context, private val prefs: Prefs) {
    interface Listener {
        fun onLevel(level: Float) {}
        fun onState(state: State, detail: String = "") {}
        fun onPartial(text: String) {}
        /** A finished sentence; [pauseMs] is the silence before it (0 for the first). */
        fun onSegment(text: String, pauseMs: Long) {}
    }

    enum class State { IDLE, LOADING, LISTENING, RECOGNIZING, ERROR }

    var listener: Listener? = null
    var state = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val decodeThread = HandlerThread("kk-voice").apply { start() }
    private val decode = Handler(decodeThread.looper)
    @Volatile private var running = false
    @Volatile private var session = 0
    private var sense: SenseVoice? = null
    private var vad: SileroVad? = null
    private var whisper = 0L
    private var whisperModel = ""
    private var system: SpeechRecognizer? = null
    @Volatile private var partialBusy = false

    /** State changes from an older session (after a cancel or restart) are dropped. */
    private fun post(s: State, detail: String = "", id: Int = session) { main.post { if (id == session) { state = s; listener?.onState(s, detail) } } }

    /** Which recognizer [lang] uses with the local engine. */
    fun usesWhisper(lang: String) = remote() == null && (lang == "ru" || (lang == "en" && prefs.voiceEnModel == "whisper"))

    /** An AI service chosen for recognition ("id|model"), or null for the phone's own models. */
    fun remote(): String? = prefs.voiceEngine.takeIf { it.contains('|') }?.let { com.kk.shurufa.online.Llm.resolve(prefs, it) }
    @Volatile private var remoteTarget: String? = null

    fun modelReady(lang: String): Boolean = when {
        prefs.voiceEngine == "system" -> true
        prefs.voiceEngine.contains('|') -> remote() != null
        usesWhisper(lang) -> VoiceModels.installed(context, VoiceModels.byId(prefs.voiceModel))
        else -> VoiceModels.SenseVoiceModel.installed(context)
    }

    fun start(lang: String) {
        if (state == State.LISTENING || state == State.LOADING) return
        session++
        if (prefs.voiceEngine == "system") startSystem(lang) else startLocal(lang)
    }

    /** Stops listening; what was said so far is still recognized and delivered. */
    fun stop() {
        if (prefs.voiceEngine == "system") system?.stopListening() else running = false
    }

    /** Stops without delivering anything more. */
    fun cancel() {
        session++
        running = false
        system?.cancel()
        runCatching { WhisperBridge.cancel() }
        main.post { state = State.IDLE; listener?.onState(State.IDLE) }
    }

    /**
     * Frees the recognizer models (hundreds of MB); they load again on the next start. Does
     * nothing while listening.
     */
    fun releaseModels() {
        if (state == State.LISTENING || state == State.LOADING) return
        decode.post {
            if (running) return@post
            sense?.close(); sense = null
            vad?.close(); vad = null
            if (whisper != 0L) { WhisperBridge.free(whisper); whisper = 0L; whisperModel = "" }
        }
    }

    fun release() {
        cancel()
        decode.post {
            sense?.close(); sense = null
            vad?.close(); vad = null
            if (whisper != 0L) { WhisperBridge.free(whisper); whisper = 0L }
        }
        decodeThread.quitSafely()
        system?.destroy(); system = null
    }

    // ---- local: VAD + SenseVoice / Whisper ----

    private fun loadModels(lang: String) {
        if (vad == null) vad = SileroVad(context.assets.open("data/silero_vad_v5.onnx").use { it.readBytes() })
        if (remoteTarget != null) return
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        if (usesWhisper(lang)) {
            val m = VoiceModels.byId(prefs.voiceModel)
            if (whisper == 0L || whisperModel != m.id) {
                WhisperBridge.ensureLoaded()
                if (whisper != 0L) WhisperBridge.free(whisper)
                whisper = WhisperBridge.init(VoiceModels.file(context, m).absolutePath)
                whisperModel = m.id
                if (whisper == 0L) error("外文模型加载失败，请在设置里重新下载")
            }
        } else if (sense == null) {
            val d = VoiceModels.SenseVoiceModel.dir(context)
            sense = SenseVoice(File(d, "model.int8.onnx"), File(d, "tokens.txt"), threads)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocal(lang: String) {
        if (!modelReady(lang)) { post(State.ERROR, if (prefs.voiceEngine.contains('|')) "这个 AI 服务还没设置好" else if (usesWhisper(lang)) "还没有下载外文模型" else "还没有下载中文模型"); return }
        remoteTarget = remote()
        val id = session
        running = true
        post(State.LOADING)
        Thread {
            // models load first (about a second the first time); the microphone opens after
            val loaded = CountDown()
            decode.post { try { loadModels(lang); loaded.ok() } catch (e: Throwable) { loaded.fail(e.message ?: "模型加载失败") } }
            loaded.await()?.let { post(State.ERROR, it, id); running = false; return@Thread }
            if (id != session || !running) return@Thread
            listen(id, lang)
        }.apply { name = "kk-mic" }.start()
    }

    @SuppressLint("MissingPermission")
    private fun listen(id: Int, lang: String) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate))
        } catch (e: Exception) { post(State.ERROR, "无法打开麦克风", id); running = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); post(State.ERROR, "无法打开麦克风", id); running = false; return }
        val v = vad!!
        v.reset()
        post(State.LISTENING, id = id)
        val chunk = FloatArray(SileroVad.CHUNK)
        // Whisper and AI services are too slow to re-run every half second: each utterance shows when done
        val whisperMode = usesWhisper(lang) || remoteTarget != null
        val seg = Segmenter({ v.probability(it) }, prefs.voicePauseMs, prefs.voiceCommitPauseMs, prefs.voiceAutoStopSec * 1000L,
            partialEveryChunks = if (whisperMode) Int.MAX_VALUE else 16)
        rec.startRecording()
        try {
            loop@ while (running && id == session) {
                var got = 0
                while (got < chunk.size) { val n = rec.read(chunk, got, chunk.size - got, AudioRecord.READ_BLOCKING); if (n <= 0) break; got += n }
                if (got < chunk.size) continue
                var sum = 0f
                for (x in chunk) sum += x * x
                val rms = sqrt(sum / chunk.size)
                main.post { listener?.onLevel((rms * 12f).coerceIn(0f, 1f)) }
                for (e in seg.feed(chunk)) when (e) {
                    is Segmenter.Event.Partial -> if (!whisperMode && !partialBusy) partialSegment(id, lang, e.pcm)
                    is Segmenter.Event.Final -> finalSegment(id, lang, e.pcm, e.pauseMs)
                    Segmenter.Event.Stop -> break@loop
                }
            }
            // whatever is still being said when stopped
            if (id == session) seg.flush()?.let { finalSegment(id, lang, it.pcm, it.pauseMs) }
        } finally {
            runCatching { rec.stop() }; rec.release()
            running = false
            decode.post { post(State.IDLE, id = id) }
        }
    }

    private fun recognize(lang: String, pcm: FloatArray): String {
        remoteTarget?.let { t -> return simplified(com.kk.shurufa.online.Llm.transcribe(prefs, t, pcm, lang)) }
        if (usesWhisper(lang)) {
            val prompt = if (lang == "ru") "Это предложение на русском языке." else "This is a sentence in English."
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            return WhisperBridge.transcribe(whisper, pcm, lang, threads, prompt).trim()
        }
        val t = sense?.recognize(pcm, lang = when (lang) { "zh", "en" -> lang; else -> "auto" }).orEmpty()
        return simplified(t)
    }

    private fun partialSegment(id: Int, lang: String, pcm: FloatArray) {
        partialBusy = true
        decode.post {
            try {
                if (id != session) return@post
                val t = recognize(lang, pcm)
                if (t.isNotEmpty() && id == session) main.post { if (id == session) listener?.onPartial(t) }
            } catch (_: Throwable) {} finally { partialBusy = false }
        }
    }

    private fun finalSegment(id: Int, lang: String, pcm: FloatArray, pauseMs: Long) {
        if (pcm.size < 16000 / 4) return
        decode.post {
            if (id != session) return@post
            val t = try { recognize(lang, pcm) } catch (e: Throwable) { post(State.ERROR, "识别失败：${e.message}"); return@post }
            main.post { if (id == session) listener?.onSegment(t, pauseMs) }
        }
    }

    private fun simplified(t: String): String {
        if (!RimeEngine.ready || t.isEmpty()) return t
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
                if (t.isNotEmpty()) listener?.onSegment(t, 0)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, prefs.voiceAutoStopSec * 1000L)
            if (tag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        }
        post(State.LISTENING)
        r.startListening(intent)
    }

    /** A one-shot result handed between threads. */
    private class CountDown {
        private val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile private var err: String? = null
        fun ok() = latch.countDown()
        fun fail(m: String) { err = m; latch.countDown() }
        fun await(): String? { latch.await(); return err }
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
    @Synchronized fun toArray(from: Int = 0, to: Int = size): FloatArray = data.copyOfRange(from.coerceIn(0, size), to.coerceIn(0, size))
    /** Forgets the first [n] samples. */
    @Synchronized fun dropFront(n: Int) {
        val k = n.coerceIn(0, size)
        System.arraycopy(data, k, data, 0, size - k); size -= k
    }
    @Synchronized fun clear() { size = 0 }
}
