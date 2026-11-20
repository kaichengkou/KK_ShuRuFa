package com.kk.shurufa.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (bundled, 2 MB): speech probability for every 32 ms of 16 kHz audio. It
 * decides where sentences end and when the speaker has stopped altogether.
 */
class SileroVad(model: ByteArray) : AutoCloseable {
    companion object { const val CHUNK = 512; private const val CONTEXT = 64 }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    private var state = FloatArray(2 * 128)
    private val context = FloatArray(CONTEXT)

    fun reset() { state = FloatArray(2 * 128); context.fill(0f) }

    /** Probability that [chunk] (512 samples) is speech. */
    fun probability(chunk: FloatArray): Float {
        val input = FloatArray(CONTEXT + CHUNK)
        System.arraycopy(context, 0, input, 0, CONTEXT)
        System.arraycopy(chunk, 0, input, CONTEXT, CHUNK)
        System.arraycopy(chunk, CHUNK - CONTEXT, context, 0, CONTEXT)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { x ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { sr ->
            session.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val p = (out[0].value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val ns = out[1].value as Array<Array<FloatArray>>
                var k = 0
                for (a in ns) for (b in a) for (v in b) state[k++] = v
                return p
            }
        }}}
    }

    override fun close() = session.close()
}
