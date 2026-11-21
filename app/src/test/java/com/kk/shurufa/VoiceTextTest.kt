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

    /** Recognizers space Chinese apart (SenseVoice's ▁, AI services): taken out, Latin words keep theirs. */
    @Test fun noSpacesInsideChinese() {
        assertEquals(0 to "动效不显，单词之间隔着一个大空格。", f("动效 不显， 单词 之间 隔着 一个 大空格 。", "", false))
        assertEquals(0 to "开放时间早上9点至下午5点。", f("开放时间早上 9 点至下午 5 点。", "", false))
        assertEquals(0 to "我用iPhone拍的", f("我用 iPhone 拍的", "", false))
        assertEquals(0 to "我用 iPhone 拍的", f("我用iPhone 拍的", "", false, pangu = true))
        assertEquals(0 to "他说 good morning 就走了", f("他说 good morning 就走了", "", false, pangu = true))
    }

    /** The AI recognizers write English punctuation; next to Chinese it becomes full-width. */
    @Test fun fullWidthPunctuation() {
        assertEquals(0 to "好的，明天见！你几点到？", f("好的, 明天见! 你几点到?", "", false))
        assertEquals(0 to "下午3.5小时，9:30开会。", f("下午3.5小时, 9:30开会.", "", false))
        assertEquals(0 to "他说“没问题”。", f("他说\"没问题\".", "", false))
        assertEquals(0 to "Hello, world.", f("Hello, world.", "", false))
    }
}
