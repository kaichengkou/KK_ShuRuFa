package com.kk.shurufa.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Kaldi-style log-mel filterbank (80 bins, 25 ms frames every 10 ms, Hamming window,
 * pre-emphasis 0.97, DC removal, 512-point FFT), followed by SenseVoice's low frame rate
 * stacking (7 frames every 6) and CMVN. Matches what the model was trained on.
 */
class Fbank(private val negMean: FloatArray, private val invStd: FloatArray) {
    private val frameLen = 400
    private val shift = 160
    private val nFft = 512
    private val nMels = 80
    private val window = FloatArray(frameLen) { (0.54 - 0.46 * cos(2 * PI * it / (frameLen - 1))).toFloat() }
    private val banks: Array<FloatArray> = melBanks()
    // FFT tables
    private val cosT = DoubleArray(nFft / 2) { cos(2 * PI * it / nFft) }
    private val sinT = DoubleArray(nFft / 2) { -sin(2 * PI * it / nFft) }
    private val rev = IntArray(nFft).also { r ->
        val bits = Integer.numberOfTrailingZeros(nFft)
        for (i in 0 until nFft) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private fun mel(f: Double) = 1127.0 * ln(1 + f / 700.0)

    private fun melBanks(): Array<FloatArray> {
        val nb = nFft / 2
        val binW = 16000.0 / nFft
        val lo = mel(20.0); val hi = mel(8000.0)
        val d = (hi - lo) / (nMels + 1)
        return Array(nMels) { m ->
            val l = lo + m * d; val c = lo + (m + 1) * d; val r = lo + (m + 2) * d
            FloatArray(nb + 1) { i ->
                if (i >= nb) 0f else {
                    val f = mel(binW * i)
                    if (f > l && f < r) (if (f <= c) (f - l) / (c - l) else (r - f) / (r - c)).toFloat() else 0f
                }
            }
        }
    }

    /** Log-mel features, one row per 10 ms. [samples] are in -1..1. */
    fun features(samples: FloatArray, from: Int = 0, to: Int = samples.size): Array<FloatArray> {
        val n = (to - from)
        if (n < frameLen) return emptyArray()
        val frames = 1 + (n - frameLen) / shift
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        val f = DoubleArray(frameLen)
        val eps = 1.1920929e-07
        return Array(frames) { t ->
            val base = from + t * shift
            var mean = 0.0
            for (i in 0 until frameLen) { f[i] = samples[base + i] * 32768.0; mean += f[i] }
            mean /= frameLen
            for (i in 0 until frameLen) f[i] -= mean
            for (i in frameLen - 1 downTo 1) f[i] -= 0.97 * f[i - 1]
            f[0] -= 0.97 * f[0]
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            for (i in 0 until frameLen) re[rev[i]] = f[i] * window[i]
            fft(re, im)
            val row = FloatArray(nMels)
            for (m in 0 until nMels) {
                val b = banks[m]
                var e = 0.0
                for (k in 0..nFft / 2) { val w = b[k]; if (w != 0f) e += w * (re[k] * re[k] + im[k] * im[k]) }
                row[m] = ln(max(e, eps)).toFloat()
            }
            row
        }
    }

    /** In-place iterative FFT; input already in bit-reversed order. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var size = 2
        while (size <= nFft) {
            val half = size / 2; val step = nFft / size
            var i = 0
            while (i < nFft) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]; val wi = sinT[j * step]
                    val a = i + j; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += size
            }
            size *= 2
        }
    }

    /** Stacks 7 frames every 6 (padding with the first and last frame) and normalizes: [T', 560]. */
    fun lfr(feats: Array<FloatArray>, m: Int = 7, n: Int = 6): Array<FloatArray> {
        if (feats.isEmpty()) return emptyArray()
        val pad = (m - 1) / 2
        val t = feats.size
        val outT = (t + n - 1) / n
        return Array(outT) { i ->
            val row = FloatArray(m * nMels)
            for (k in 0 until m) {
                val src = (i * n + k - pad).coerceIn(0, t - 1)
                System.arraycopy(feats[src], 0, row, k * nMels, nMels)
            }
            for (j in row.indices) row[j] = (row[j] + negMean[j]) * invStd[j]
            row
        }
    }
}
