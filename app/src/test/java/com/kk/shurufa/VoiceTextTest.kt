package com.kk.shurufa

import com.kk.shurufa.voice.VoiceText
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {
    private fun f(raw: String, before: String, cont: Boolean, style: String = "full", pangu: Boolean = false, fillers: Boolean = true) =
        VoiceText.format(raw, before, cont, style, pangu, fillers).let { it.deleteBefore to it.insert }

    @Test fun fullStyleKeepsTheModelsPunctuation() {
        assertEquals(0 to "今天天气不错，我们出去走走吧。", f("今天天气不错，我们出去走走吧。", "", false))
        assertEquals(0 to "晚上吃什么？", f("晚上吃什么？", "我们出去走走吧。", true))
    }

    @Test fun chatStyle() {
        assertEquals(0 to "今天天气不错", f("今天天气不错。", "", false, "chat"))
        assertEquals(0 to "，出去走走吧", f("出去走走吧。", "今天天气不错", true, "chat"))
        assertEquals(0 to "晚上吃什么？", f("晚上吃什么？", "出去走走吧？", true, "chat"))
    }

    @Test fun noPunctuation() {
        assertEquals(0 to "今天 天气不错", f("今天，天气不错。", "", false, "none"))
        assertEquals(0 to " 出去走走吧", f("出去走走吧。", "今天 天气不错", true, "none"))
    }

    @Test fun fillers() {
        assertEquals(0 to "明天我们去公园吧。", f("嗯，明天我们去公园吧。", "", false))
        assertEquals(0 to "明天，我们去公园吧。", f("明天，呃，我们去公园吧。", "", false))
        assertEquals(0 to "嗯。", f("嗯。", "", false))
        assertEquals(0 to "嗯，明天吧。", f("嗯，明天吧。", "", false, fillers = false))
    }

    @Test fun latinAndSpacing() {
        assertEquals(0 to "Hello there.", f("hello there.", "", false))
        assertEquals(0 to " How are you?", f("how are you?", "Hello there.", true))
        assertEquals("我用 iPhone 拍了 3 张照片", VoiceText.spaced("我用iPhone拍了3张照片"))
        assertEquals(0 to "我用 iPhone 拍的。", f("我用iPhone拍的。", "", false, pangu = true))
    }
}
