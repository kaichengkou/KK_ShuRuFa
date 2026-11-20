package com.kk.shurufa.voice

/**
 * Cuts a live 16 kHz stream into sentences with a voice-activity detector. Fed 512-sample
 * chunks, it reports a growing sentence for live display ([Event.Partial]), each finished
 * sentence with the pause before it ([Event.Final]), and [Event.Stop] once nobody has spoken
 * for [autoStopMs]. Time is counted in samples, so the result does not depend on the clock.
 */
class Segmenter(
    private val vad: (FloatArray) -> Float,
    pauseMs: Int,
    private val autoStopMs: Long,
    private val partialEveryChunks: Int = 16,
    private val maxSentenceSec: Int = 25,
) {
    sealed class Event {
        class Partial(val pcm: FloatArray) : Event()
        class Final(val pcm: FloatArray, val pauseMs: Long) : Event()
        object Stop : Event()
    }

    private val chunkMs = SileroVad.CHUNK / 16L
    private val endChunks = (pauseMs / chunkMs).toInt().coerceAtLeast(8)
    private val preRoll = 10 * SileroVad.CHUNK
    private val audio = FloatArrayBuilder()
    private var speaking = false
    private var segStart = 0
    private var silence = 0
    private var voiced = 0
    private var sinceLast = 0
    private var now = 0L
    private var lastSpeechEnd = 0L
    private var speechStart = 0L
    private var heardAny = false

    val isSpeaking get() = speaking

    fun feed(chunk: FloatArray): List<Event> {
        now += chunkMs
        audio.add(chunk, chunk.size)
        val p = vad(chunk)
        if (!speaking) {
            voiced = if (p > 0.5f) voiced + 1 else 0
            if (voiced >= 2) {
                speaking = true; silence = 0; sinceLast = 0
                speechStart = now - voiced * chunkMs
                segStart = (audio.size - voiced * SileroVad.CHUNK - preRoll).coerceAtLeast(0)
                return emptyList()
            }
            // keep only a little silence before the next sentence
            if (audio.size > preRoll * 3) audio.dropFront(audio.size - preRoll)
            // before anything was said, allow a few seconds more to start
            val idle = now - (if (heardAny) lastSpeechEnd else 0L)
            return if (autoStopMs > 0 && idle > autoStopMs + (if (heardAny) 0 else 4000)) listOf(Event.Stop) else emptyList()
        }
        silence = if (p < 0.35f) silence + 1 else 0
        sinceLast++
        if (silence >= endChunks || audio.size - segStart > maxSentenceSec * 16000) return listOf(finish(trailing = silence))
        if (sinceLast >= partialEveryChunks) { sinceLast = 0; return listOf(Event.Partial(audio.toArray(segStart, audio.size))) }
        return emptyList()
    }

    /** The sentence being spoken when the stream ends, if any. */
    fun flush(): Event.Final? = if (speaking) finish(trailing = 0) else null

    private fun finish(trailing: Int): Event.Final {
        val segEnd = audio.size - (trailing - 3).coerceAtLeast(0) * SileroVad.CHUNK
        val pause = if (heardAny) (speechStart - lastSpeechEnd).coerceAtLeast(0L) else 0L
        val e = Event.Final(audio.toArray(segStart, segEnd), pause)
        heardAny = true
        lastSpeechEnd = now - trailing * chunkMs
        speaking = false; voiced = 0
        audio.dropFront((segEnd - preRoll).coerceAtLeast(0))
        return e
    }
}
