package com.kk.shurufa

import com.kk.shurufa.voice.SenseVoice
import com.kk.shurufa.voice.SileroVad
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end check of the Kotlin speech pipeline. The 240 MB model is not in the repository:
 * set KK_SENSEVOICE_DIR to a folder with model.int8.onnx and tokens.txt to run it.
 */
class SenseVoiceTest {
    private fun wav(f: File): FloatArray {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b, 44, b.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((b.size - 44) / 2) { bb.short / 32768f }
    }
    private val res get() = File(javaClass.classLoader!!.getResource("voice/zh.wav")!!.toURI()).parentFile

    @Test fun recognizesChinese() {
        val dir = System.getenv("KK_SENSEVOICE_DIR")?.let { File(it) }
        assumeTrue("KK_SENSEVOICE_DIR not set", dir != null && File(dir, "model.int8.onnx").isFile)
        SenseVoice(File(dir!!, "model.int8.onnx"), File(dir, "tokens.txt"), 4).use { sv ->
            assertEquals("开饭时间早上9点至下午5点。", sv.recognize(wav(File(res, "zh.wav")), lang = "zh"))
        }
    }

    @Test fun vadFindsSpeech() {
        val model = File("src/main/assets/data/silero_vad_v5.onnx").readBytes()
        val a = FloatArray(8000) + wav(File(res, "zh.wav")) + FloatArray(16000)
        SileroVad(model).use { vad ->
            val p = (0 until a.size / SileroVad.CHUNK).map { i -> vad.probability(a.copyOfRange(i * SileroVad.CHUNK, (i + 1) * SileroVad.CHUNK)) }
            // silence before, speech in the middle, silence after
            assertEquals(true, p.take(10).all { it < 0.3f })
            assertEquals(true, p.subList(40, 80).count { it > 0.5f } > 30)
            assertEquals(true, p.takeLast(20).all { it < 0.3f })
        }
    }

    private fun stream(commitPauseMs: Int, sv: SenseVoice?): Triple<List<Pair<String, Long>>, Int, Int> {
        val model = File("src/main/assets/data/silero_vad_v5.onnx").readBytes()
        val speech = wav(File(res, "zh.wav"))
        // a sentence, 1.5 s of thinking, the sentence again, then silence
        val a = FloatArray(8000) + speech + FloatArray(24000) + speech + FloatArray(16000 * 8)
        SileroVad(model).use { vad ->
            val seg = com.kk.shurufa.voice.Segmenter({ vad.probability(it) }, pauseMs = 700, commitPauseMs = commitPauseMs, autoStopMs = 3000)
            val finals = mutableListOf<Pair<String, Long>>()
            var partials = 0
            var stoppedAt = -1
            for (i in 0 until a.size / SileroVad.CHUNK) {
                for (e in seg.feed(a.copyOfRange(i * SileroVad.CHUNK, (i + 1) * SileroVad.CHUNK))) when (e) {
                    is com.kk.shurufa.voice.Segmenter.Event.Partial -> partials++
                    is com.kk.shurufa.voice.Segmenter.Event.Final -> finals += (sv?.recognize(e.pcm, lang = "zh") ?: "${e.pcm.size}") to e.pauseMs
                    com.kk.shurufa.voice.Segmenter.Event.Stop -> stoppedAt = i
                }
                if (stoppedAt >= 0) break
            }
            println("commit=$commitPauseMs finals=$finals partials=$partials stoppedAt=${stoppedAt * 32} ms")
            return Triple(finals, partials, stoppedAt * 32)
        }
    }

    /** A pause shorter than the commit pause is thinking time: one utterance, recognized as a whole. */
    @Test fun thinkingPauseDoesNotCut() {
        val dir = System.getenv("KK_SENSEVOICE_DIR")?.let { File(it) }?.takeIf { File(it, "model.int8.onnx").isFile }
        val sv = dir?.let { SenseVoice(File(it, "model.int8.onnx"), File(it, "tokens.txt"), 4) }
        val (one, partials, stopped) = stream(4000, sv)
        assertEquals(1, one.size)
        assertEquals(true, partials >= 4)
        assertEquals(true, stopped in 14500..19500)
        if (sv != null) assertEquals(true, Regex("时间早上9点至下午5点").findAll(one[0].first).count() == 2)
        // a commit pause shorter than the gap: two utterances
        val (two, _, _) = stream(1800, sv)
        assertEquals(2, two.size)
        sv?.close()
    }
}
