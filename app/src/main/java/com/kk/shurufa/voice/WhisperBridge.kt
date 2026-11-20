package com.kk.shurufa.voice

/** whisper.cpp, loaded on first use (the library is only needed for voice input). */
object WhisperBridge {
    @Volatile private var loaded = false
    fun ensureLoaded() { if (!loaded) synchronized(this) { if (!loaded) { System.loadLibrary("kk_whisper"); loaded = true } } }

    external fun init(modelPath: String): Long
    external fun free(ctx: Long)
    external fun cancel()
    /** Transcribes 16 kHz mono samples; [lang] is "zh", "en", "ru" or "auto". */
    external fun transcribe(ctx: Long, pcm: FloatArray, lang: String, threads: Int, prompt: String): String
    external fun lastLanguage(ctx: Long): String
}
