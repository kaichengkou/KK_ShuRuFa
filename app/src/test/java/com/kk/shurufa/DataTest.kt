package com.kk.shurufa

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.PinyinTones
import com.kk.shurufa.ime.Predictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Checks the generated 联想 index and tone table that ship in the APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun associations() {
        val p = Predictor(ctx)
        p.load()
        var tries = 0
        while (p.lookup("中国").isEmpty() && tries++ < 200) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(25) }
        val zg = p.lookup("中国")
        println("中国 -> $zg")
        assertTrue(zg.contains("人民"))
        assertTrue(p.lookup("苹果").contains("电脑"))
        // the last characters of a longer text are used
        assertTrue(p.suggest("我爱中国").isNotEmpty())
        assertTrue(p.suggest("hello").isEmpty())
    }

    @Test fun poetry() {
        val p = Predictor(ctx)
        p.loadPoetry()
        var tries = 0
        while (p.nextLine("床前明月光") == null && tries++ < 200) Thread.sleep(25)
        assertEquals("疑是地上霜", p.nextLine("床前明月光"))
        assertEquals("黄河入海流", p.nextLine("我想起白日依山尽，"))
        assertEquals("在河之洲", p.nextLine("关关雎鸠"))
        assertTrue(p.suggest("白日依山尽").first() == "黄河入海流")
    }

    @Test fun tones() {
        val t = PinyinTones(ctx)
        assertEquals("nǐ hǎo", t.convert("你好", listOf("ni", "hao")))
        // polyphones follow what was typed
        assertEquals("yín háng", t.convert("银行", listOf("yin", "hang")))
        assertEquals("xíng zǒu", t.convert("行走", listOf("xing", "zou")))
        assertEquals("yīn yuè", t.convert("音乐", listOf("yin", "yue")))
        assertEquals("kuài lè", t.convert("快乐", listOf("kuai", "le")))
        // ü typed as v or shown as u after j/q/x/y
        assertEquals("lǜ sè", t.convert("绿色", listOf("lv", "se")))
        assertEquals("nǐ hǎo，shì jiè", t.convert("你好，世界", listOf("ni", "hao", "shi", "jie")))
    }

    @Test fun emojiSearch() {
        val d = com.kk.shurufa.ime.EmojiData(ctx).apply { load() }
        assertTrue(d.version, com.kk.shurufa.ime.EmojiData.newer(d.version, "15.1"))
        assertTrue(d.search("开心").take(10).contains("😄"))
        assertTrue(d.search("开心").none { it == "🙁" })
        assertEquals("🐈", d.search("cat").first())
        assertTrue(d.search("猫").take(12).contains("🐱"))
        // a phrase finds the words inside it
        assertTrue(d.search("今天好开心").take(20).contains("😄"))
        assertEquals("😂", d.search("笑哭").first())
        // one entry per emoji: tones and man/woman forms are only in the long-press menu
        val all = d.groups.flatMap { it.second }
        assertTrue("👋" in all && "👋🏻" !in all && "👮‍♀️" !in all)
        assertEquals(5, d.variants["👋"]!!.size)
        assertTrue(d.variants["👮"]!!.containsAll(listOf("👮‍♂️", "👮‍♀️", "👮🏽‍♀️")))
        assertTrue(d.search("ЖЖЖ").toString(), d.search("ЖЖЖ").isEmpty())
        assertTrue(com.kk.shurufa.ime.EmojiData.newer("18.0", "16.0"))
        assertTrue(!com.kk.shurufa.ime.EmojiData.newer("16.0", "16.0"))
    }
}
