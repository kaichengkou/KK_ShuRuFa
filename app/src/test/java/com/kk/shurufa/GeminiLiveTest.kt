package com.kk.shurufa

import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.online.AiWriter
import com.kk.shurufa.online.Llm
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Talks to the real Gemini API through the same code the keyboard uses. Needs a key in
 * KK_GEMINI_KEY (never stored in the repository); skipped otherwise. KK_GEMINI_MODELS picks
 * the models to compare (comma separated).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveTest {
    private val key = System.getenv("KK_GEMINI_KEY")
    private val models = (System.getenv("KK_GEMINI_MODELS") ?: "gemini-2.5-flash").split(',').map { it.trim() }

    private fun prefs(): Prefs {
        assumeTrue("KK_GEMINI_KEY not set", !key.isNullOrBlank())
        return Prefs(ApplicationProvider.getApplicationContext()).also { it.put("llm_gemini_key", key!!) }
    }

    private fun timed(label: String, f: () -> String) {
        val t = System.currentTimeMillis()
        val out = runCatching(f).getOrElse { "!! ${it.message}" }
        println("[gemini] $label  ${System.currentTimeMillis() - t} ms  →  ${out.replace("\n", "⏎")}")
    }

    /** A phone set up before the old default was retired still gets answers, and moves on. */
    @Test fun retiredDefault() {
        val p = prefs(); p.put("llm_gemini_model", "gemini-2.5-flash")
        timed("旧默认 gemini-2.5-flash") { Llm.complete(p, "gemini", AiWriter.SYSTEM, AiWriter.prompt(AiWriter.ACTIONS[1], "我今天很高心")) }
        println("[gemini] default now: ${Llm.model(p, "gemini")}")
        org.junit.Assert.assertNotEquals("gemini-2.5-flash", Llm.model(p, "gemini"))
    }

    /** AI 候选: a pause on longer pinyin brings the service's reading of it. */
    @Test fun aiCandidate() {
        val p = prefs(); p.put(Prefs.AI_CANDIDATE, true); p.put(Prefs.AI_PROVIDER, "gemini|gemini-3.5-flash-lite")
        var ready = 0
        val ai = com.kk.shurufa.ime.AiCandidate(p) { ready++ }
        val input = "womenzaixuexiaomenkoujianmian"
        val t0 = System.currentTimeMillis()
        ai.update(input, 8, "我们在学校门口", "明天下午", allowed = true)
        while (ai.answerFor(input) == null && System.currentTimeMillis() - t0 < 20_000) {
            Thread.sleep(50); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        println("[gemini] AI 候选 ${System.currentTimeMillis() - t0} ms → ${ai.answerFor(input)}")
        org.junit.Assert.assertEquals("我们在学校门口见面", ai.answerFor(input)?.trimEnd('。'))
        org.junit.Assert.assertEquals(1, ready)
        // initials only: what comes back is not Chinese, so nothing is offered
        ai.update("wjdkyzsysbxyxgygxdfr", 20, "", "", allowed = true)
        // not where learning is not allowed
        ai.clear(); ai.update(input, 8, "我们在学校门口", "", allowed = false)
        Thread.sleep(800); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        org.junit.Assert.assertNull(ai.answerFor(input))
    }

    /** 云联想: a pause after some text brings three continuations of it. */
    @Test fun cloudPredict() {
        val p = prefs(); p.put(Prefs.CLOUD_PREDICT, true); p.put(Prefs.AI_PROVIDER, "gemini|gemini-3.5-flash-lite")
        val cloud = com.kk.shurufa.ime.CloudPredict(p) {}
        for (text in listOf("我想去北京", "спасибо за ", "how are ")) {
            val t0 = System.currentTimeMillis()
            cloud.update(text, allowed = true)
            while (cloud.answerFor(text).isEmpty() && System.currentTimeMillis() - t0 < 20_000) {
                Thread.sleep(40); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
            }
            println("[gemini] 云联想 ${System.currentTimeMillis() - t0} ms  $text → ${cloud.answerFor(text)}")
            org.junit.Assert.assertTrue(text, cloud.answerFor(text).size in 1..3)
        }
    }

    @Test fun live() {
        val p = prefs()
        val listed = Llm.listModels(p, "gemini")
        println("[gemini] listModels: ${listed.size} models, e.g. ${listed.take(8)}")
        val text = "我昨天去那个店里面买东西，然后发现他们家的东西其实挺贵的，但是质量还行吧，就是服务态度不咋地。"
        for (m in models) {
            var first = -1L; val t0 = System.currentTimeMillis()
            timed("$m 润色") { Llm.complete(p, "gemini", AiWriter.SYSTEM, AiWriter.prompt(AiWriter.ACTIONS[0], text), onText = { if (first < 0) first = System.currentTimeMillis() - t0 }, model = m) }
            println("[gemini]   first token after $first ms")
            timed("$m 俄语") { Llm.complete(p, "gemini", AiWriter.translateSystem("Chinese", "Russian"), "我昨天很累，所以早早就睡了。", model = m) }
            timed("$m 指令") { Llm.complete(p, "gemini", AiWriter.SYSTEM, AiWriter.prompt(AiWriter.custom("改成一句道歉的话"), "明天的会我去不了"), model = m) }
            val b = File(javaClass.classLoader!!.getResource("voice/zh.wav")!!.toURI()).readBytes()
            val bb = ByteBuffer.wrap(b, 44, b.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = FloatArray((b.size - 44) / 2) { bb.short / 32768f }
            timed("$m 语音") { Llm.transcribe(p, "gemini|$m", pcm, "zh") }
        }
    }
}
