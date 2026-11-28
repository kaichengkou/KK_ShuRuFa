package com.kk.shurufa

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.CloudPredict
import com.kk.shurufa.online.Llm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 云联想 and the AI requests against a stand-in for Gemini on this machine: a model that says
 * nothing (gemini-3.5-flash here) and a quick one (Flash Lite, answering after [LITE_MS]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FastAnswersTest {
    private lateinit var server: ServerSocket
    private val asked = CopyOnWriteArrayList<String>()
    private lateinit var p: Prefs

    /** One request: its line, headers and body read; the answer written; the connection closed. */
    private fun serve(sock: Socket) = sock.use {
        val inp = BufferedInputStream(sock.getInputStream())
        fun line(): String { val b = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
        val req = line()
        var length = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt() }
        val body = ByteArray(length).also { var n = 0; while (n < length) { val r = inp.read(it, n, length - n); if (r < 0) break; n += r } }.toString(Charsets.UTF_8)
        val out = sock.getOutputStream()
        if (req.startsWith("HEAD")) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use }
        val path = req.split(' ')[1]
        val text = Regex("\"text\":\"([^\"]*)\"").findAll(body).last().groupValues[1]
        asked += "${path.substringAfter("models/").substringBefore(':')} $text"
        if (!path.contains("flash-lite")) { runCatching { Thread.sleep(30_000) }; return@use }
        Thread.sleep(LITE_MS)
        val answer = if (text.endsWith("你好")) "世界和平\\n朋友们\\n大家好" else "好的"
        val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$answer\"}]}}]}\n\n".toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${sse.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(sse); out.flush()
    }

    @Before fun up() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread { while (!server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break; Thread { runCatching { serve(s) } }.apply { isDaemon = true }.start() } }.apply { isDaemon = true }.start()
        // on Wi-Fi (Robolectric's network is mobile data)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, android.net.NetworkInfo.State.CONNECTED))
        p = Prefs(ctx)
        // other tests may have spent this minute's requests
        com.kk.shurufa.online.AiBudget.reset()
        println("[fast] metered: ${p.metered()}")
        p.put("llm_gemini_key", "test")
        p.put("llm_gemini_base", "http://127.0.0.1:${server.localPort}/v1beta")
    }

    @After fun down() = server.close()

    private fun until(ms: Long, done: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (!done() && System.currentTimeMillis() - t0 < ms) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        return System.currentTimeMillis() - t0
    }

    /** A model that says nothing is given up after its first-word time, and a lighter one answers. */
    @Test fun slowModelFallsBack() {
        p.put("llm_gemini_model", "gemini-3.5-flash")
        val t0 = System.currentTimeMillis()
        assertEquals("好的", Llm.complete(p, "gemini", "sys", "改写这句"))
        val first = System.currentTimeMillis() - t0
        println("[fast] 首字超时后回退: $first ms, ${asked.toList()}")
        assertTrue("$first", first in 5_500L..9_000L)
        assertEquals(listOf("gemini-3.5-flash 改写这句", "gemini-3.5-flash-lite 改写这句"), asked.toList())
        // for a while the slow one is passed over: straight to the light one
        val t1 = System.currentTimeMillis()
        assertEquals("好的", Llm.complete(p, "gemini", "sys", "再来"))
        assertTrue(System.currentTimeMillis() - t1 < 2_000)
        assertEquals("gemini-3.5-flash-lite 再来", asked.last())
        // while typing (云联想, AI 候选) the same: the light one, at once
        assertEquals("好的", Llm.completeQuick(p, "gemini|gemini-3.5-flash", "sys", "打字时"))
        assertEquals("gemini-3.5-flash-lite 打字时", asked.last())
    }

    /** 云联想 asked ahead while pinyin waits; space then finds it on its way, and what follows it is free. */
    @Test fun cloudAskedAhead() {
        p.put(Prefs.AI_ASSIST, "cloud"); p.put(Prefs.AI_PROVIDER, "gemini|gemini-3.5-flash-lite")
        var ready = 0
        val cloud = CloudPredict(p) { ready++ }
        // "nihao" waits on the bar with 你好 first; the text before is 我说
        cloud.prefetch("你好", { "我说" }, allowed = true)
        until(CloudPredict.AHEAD_PAUSE_MS + 100) { asked.isNotEmpty() }
        assertEquals(listOf("gemini-3.5-flash-lite 我说你好"), asked.toList())
        // space takes 你好: the continuations are on their way, nothing is asked again
        val waited = until(3_000) { cloud.answerFor("我说你好").isNotEmpty() }
        println("[fast] 云联想 after space: $waited ms (service ${LITE_MS} ms, asked ${CloudPredict.AHEAD_PAUSE_MS} ms ahead)")
        cloud.update("我说你好", allowed = true)
        assertEquals(listOf("世界和平", "朋友们", "大家好"), cloud.answerFor("我说你好"))
        assertEquals(1, asked.size)
        // typing 世界 next: 和平 at once, without asking
        cloud.update("我说你好世界", allowed = true)
        assertEquals(listOf("和平"), cloud.answerFor("我说你好世界"))
        until(600) { false }
        assertEquals(1, asked.size)
    }

    /** Without asking ahead: a short pause, and none after the end of a sentence. */
    @Test fun cloudPauses() {
        p.put(Prefs.AI_ASSIST, "cloud"); p.put(Prefs.AI_PROVIDER, "gemini|gemini-3.5-flash-lite")
        val cloud = CloudPredict(p) {}
        val t0 = System.currentTimeMillis()
        cloud.update("我想说你好", allowed = true)
        val waited = until(3_000) { cloud.answerFor("我想说你好").isNotEmpty() }
        println("[fast] 云联想 after a pause: $waited ms (pause ${p.cloudPauseNowMs} ms, service ${LITE_MS} ms)")
        assertTrue("$waited", waited < 1_000)
        cloud.update("好的。", allowed = true)
        until(LITE_MS / 2) { asked.size > 1 }
        assertEquals("gemini-3.5-flash-lite 好的。", asked.last())
        assertTrue(System.currentTimeMillis() - t0 > 0)
    }

    companion object { const val LITE_MS = 400L }
}
