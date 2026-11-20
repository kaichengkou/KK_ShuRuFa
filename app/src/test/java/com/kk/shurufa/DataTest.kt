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
}
