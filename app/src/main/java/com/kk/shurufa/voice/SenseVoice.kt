package com.kk.shurufa.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * SenseVoice-Small (FunAudioLLM): a non-autoregressive model trained mostly on Chinese, with
 * punctuation and number formatting built in. One pass over a few seconds of audio takes tens
 * of milliseconds, which is what makes the live partial results possible.
 */
class SenseVoice(modelFile: File, tokensFile: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    private val meta = session.metadata.customMetadata
    private val fbank = Fbank(floats("neg_mean"), floats("inv_stddev"))
    private val tokens: List<String> = tokensFile.readLines().map { it.substringBeforeLast(' ') }

    private fun floats(k: String) = meta[k]!!.split(',').map { it.trim().toFloat() }.toFloatArray()
    private fun id(k: String, def: Int) = meta[k]?.toIntOrNull() ?: def

    /** [lang] "zh", "en", "yue", "ja", "ko" or "auto". */
    fun recognize(samples: FloatArray, from: Int = 0, to: Int = samples.size, lang: String = "auto", itn: Boolean = true): String {
        val x = fbank.lfr(fbank.features(samples, from, to))
        if (x.isEmpty()) return ""
        val t = x.size; val d = x[0].size
        val buf = FloatBuffer.allocate(t * d)
        for (row in x) buf.put(row)
        buf.rewind()
        val langId = id("lang_$lang", 0)
        val norm = if (itn) id("with_itn", 14) else id("without_itn", 15)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, t.toLong(), d.toLong())).use { xt ->
        OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(t)), longArrayOf(1)).use { lt ->
        OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(langId)), longArrayOf(1)).use { gt ->
        OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(norm)), longArrayOf(1)).use { nt ->
            session.run(mapOf("x" to xt, "x_length" to lt, "language" to gt, "text_norm" to nt)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                return decode(logits)
            }
        }}}}
    }

    /** CTC greedy decoding; the first four outputs are the language/emotion/event/ITN tags. */
    private fun decode(logits: Array<FloatArray>): String {
        val ids = ArrayList<Int>()
        var prev = -1
        for (row in logits) {
            var best = 0; var bv = row[0]
            for (k in 1 until row.size) if (row[k] > bv) { bv = row[k]; best = k }
            if (best != prev && best != 0) ids += best
            prev = best
        }
        val sb = StringBuilder()
        for (k in ids.drop(4)) { val s = tokens.getOrNull(k) ?: continue; if (!s.startsWith("<|")) sb.append(s) }
        return sb.toString().replace('▁', ' ').trim()
    }

    override fun close() = session.close()
}
