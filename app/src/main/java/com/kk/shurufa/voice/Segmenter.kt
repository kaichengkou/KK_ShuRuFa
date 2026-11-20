package com.kk.shurufa.voice

/**
 * Turns a live 16 kHz stream into utterances. A pause for thinking does not end anything: the
 * utterance keeps growing across pauses and is recognized again as a whole, so the model, seeing
 * the full context, decides where commas and full stops go. Only a long pause ([commitPauseMs])
 * or the end of the stream finishes it ([Event.Final]). Silences inside an utterance are shortened
 * to [keepGapMs] before recognition, so thinking time costs no extra work.
 *
 * Fed 512-sample chunks; time is counted in samples, so results do not depend on the clock.
 */
class Segmenter(
    private val vad: (FloatArray) -> Float,
    pauseMs: Int,
    commitPauseMs: Int,
    private val autoStopMs: Long,
    private val partialEveryChunks: Int = 16,
    private val maxUtteranceSec: Int = 24,
    keepGapMs: Int = 400,
) {
    sealed class Event {
        class Partial(val pcm: FloatArray) : Event()
        class Final(val pcm: FloatArray, val pauseMs: Long) : Event()
        object Stop : Event()
    }

    private val chunkMs = SileroVad.CHUNK / 16L
    /** A pause this long refreshes the text (the model may now place a comma). */
    private val pauseChunks = (pauseMs / chunkMs).toInt().coerceAtLeast(6)
    private val commitChunks = (commitPauseMs / chunkMs).toInt().coerceAtLeast(pauseChunks + 1)
    private val keepGapChunks = (keepGapMs / chunkMs).toInt()
    private val preRollChunks = 10
    private val pre = ArrayDeque<FloatArray>()
    private val audio = FloatArrayBuilder()
    private var inUtterance = false
    private var voiced = 0
    private var silence = 0
    private var sinceLast = 0
    private var refreshedAtPause = false
    private var now = 0L
    private var lastSpeechEnd = 0L
    private var utteranceStart = 0L
    private var heardAny = false

    fun feed(chunk: FloatArray): List<Event> {
        now += chunkMs
        val p = vad(chunk)
        val speech = p > 0.5f
        if (!inUtterance) {
            pre.addLast(chunk.copyOf()); if (pre.size > preRollChunks) pre.removeFirst()
            voiced = if (speech) voiced + 1 else 0
            if (voiced >= 2) {
                inUtterance = true; silence = 0; sinceLast = 0; refreshedAtPause = false
                utteranceStart = now - voiced * chunkMs
                audio.clear()
                for (c in pre) audio.add(c, c.size)
                pre.clear()
                return emptyList()
            }
            val idle = now - (if (heardAny) lastSpeechEnd else 0L)
            // before anything was said, allow a few seconds more to start
            return if (autoStopMs > 0 && idle > autoStopMs + (if (heardAny) 0 else 4000)) listOf(Event.Stop) else emptyList()
        }
        silence = if (p < 0.35f) silence + 1 else 0
        // thinking time is not kept beyond a short gap
        if (silence <= keepGapChunks) audio.add(chunk, chunk.size)
        if (silence == 0) refreshedAtPause = false
        val events = ArrayList<Event>(1)
        when {
            silence >= commitChunks || audio.size > maxUtteranceSec * 16000 -> events += finish()
            silence >= pauseChunks && !refreshedAtPause -> { refreshedAtPause = true; sinceLast = 0; events += Event.Partial(audio.toArray()) }
            silence == 0 && ++sinceLast >= partialEveryChunks -> { sinceLast = 0; events += Event.Partial(audio.toArray()) }
        }
        return events
    }

    /** The utterance still open when the stream ends, if any. */
    fun flush(): Event.Final? = if (inUtterance) finish() else null

    private fun finish(): Event.Final {
        val pause = if (heardAny) (utteranceStart - lastSpeechEnd).coerceAtLeast(0L) else 0L
        val e = Event.Final(audio.toArray(), pause)
        heardAny = true
        lastSpeechEnd = now - silence * chunkMs
        inUtterance = false; voiced = 0
        audio.clear()
        return e
    }
}
