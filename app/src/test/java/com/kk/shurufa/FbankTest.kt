package com.kk.shurufa

import com.kk.shurufa.voice.Fbank
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** The Kotlin features must match the reference implementation the model was verified with. */
class FbankTest {
    private fun wav(f: File): FloatArray {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b, 44, b.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((b.size - 44) / 2) { bb.short / 32768f }
    }

    @Test fun matchesReference() {
        val dir = File(javaClass.classLoader!!.getResource("voice/ref.txt")!!.toURI()).parentFile
        val ref = File(dir, "ref.txt").readLines()
        val neg = ref[0].split(',').map { it.toFloat() }.toFloatArray()
        val inv = ref[1].split(',').map { it.toFloat() }.toFloatArray()
        val (nf, nl) = ref[2].split(' ').map { it.toInt() }
        val fb = Fbank(neg, inv)
        val feats = fb.features(wav(File(dir, "zh.wav")))
        val x = fb.lfr(feats)
        assertEquals(nf, feats.size)
        assertEquals(nl, x.size)
        ref[3].split(' ').map { it.toFloat() }.forEachIndexed { i, v -> assertEquals("fbank[50][$i]", v, feats[50][i], 2e-3f) }
        ref[4].split(' ').map { it.toFloat() }.forEachIndexed { i, v -> assertEquals("lfr[20][${300 + i}]", v, x[20][300 + i], 2e-3f) }
        val mean = x.sumOf { r -> r.sumOf { abs(it).toDouble() } } / (x.size * x[0].size)
        assertEquals(ref[5].toDouble(), mean, 1e-4)
    }
}
