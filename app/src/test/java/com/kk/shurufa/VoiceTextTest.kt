package com.kk.shurufa

import com.kk.shurufa.voice.VoiceText
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {
    private fun f(raw: String, before: String, pause: Long, cont: Boolean, style: String = "full", join: Boolean = true, pangu: Boolean = false) =
        VoiceText.format(raw, before, pause, cont, style, join, pangu).let { it.deleteBefore to it.insert }

    @Test fun fullStyle() {
        assertEquals(0 to "今天天气不错。", f("今天天气不错。", "", 0, false))
        // a short pause continues the sentence: 。 becomes ，
        assertEquals(1 to "，我们出去走走吧。", f("我们出去走走吧。", "今天天气不错。", 500, true))
        // a long pause is a new sentence
        assertEquals(0 to "我们出去走走吧。", f("我们出去走走吧。", "今天天气不错。", 1500, true))
        assertEquals(0 to "我们出去走走吧。", f("我们出去走走吧。", "今天天气不错。", 500, true, join = false))
    }

    @Test fun chatStyle() {
        assertEquals(0 to "今天天气不错", f("今天天气不错。", "", 0, false, "chat"))
        assertEquals(0 to "，出去走走吧", f("出去走走吧。", "今天天气不错", 600, true, "chat"))
        assertEquals(0 to " 晚上吃什么？", f("晚上吃什么？", "今天天气不错", 4000, true, "chat"))
    }

    @Test fun noPunctuation() {
        assertEquals(0 to "今天 天气不错", f("今天，天气不错。", "", 0, false, "none"))
        assertEquals(0 to " 出去走走吧", f("出去走走吧。", "今天 天气不错", 600, true, "none"))
    }

    @Test fun latinAndSpacing() {
        assertEquals(0 to "Hello there.", f("hello there.", "", 0, false))
        assertEquals(0 to " How are you?", f("how are you?", "Hello there.", 400, true))
        assertEquals("我用 iPhone 拍了 3 张照片", VoiceText.spaced("我用iPhone拍了3张照片"))
        assertEquals(0 to "我用 iPhone 拍的。", f("我用iPhone拍的。", "", 0, false, pangu = true))
    }
}
