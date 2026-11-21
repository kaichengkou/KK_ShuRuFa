package com.kk.shurufa

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.TypingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 打字数据: what is counted, how words are split out, and that clearing forgets everything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypingStatsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun settle() { repeat(20) { Thread.sleep(25); shadowOf(Looper.getMainLooper()).idle() } }

    @Test fun countsAndWords() {
        File(ctx.filesDir, "typing_stats.json").delete()
        TypingStats.reload()
        val s = TypingStats.get(ctx)
        s.record("今天天气真好，我们去公园散步", TypingStats.Source.TYPED)
        s.record("今天的会议改到下午", TypingStats.Source.TYPED)
        s.record("刚才说的那家餐厅", TypingStats.Source.VOICE)
        s.record("https://example.com", TypingStats.Source.PASTE)
        s.record("哈哈😂", TypingStats.Source.TYPED)
        settle()
        val snap = s.snapshot()
        assertEquals("characters are counted by source, spaces aside", 14 + 9, snap.bySource(TypingStats.Source.TYPED) - 3)
        assertEquals(8, snap.bySource(TypingStats.Source.VOICE))
        val words = snap.words.toMap()
        assertTrue("今天" in words)
        assertTrue("words come out of whole phrases: $words", "公园" in words && "会议" in words && "餐厅" in words)
        assertTrue("everyday function words stay out of the cloud", "我们" !in words)
        assertTrue("pasted text is counted, not mined for words", words.keys.none { it.contains("example") })
        assertEquals("😂", snap.emoji.first().first)
        assertEquals(1, snap.streak)
        // kept across a restart, and gone when cleared
        s.save(); TypingStats.reload()
        assertEquals(snap.total, TypingStats.get(ctx).snapshot().total)
        TypingStats.get(ctx).clear(); settle()
        assertEquals(0, TypingStats.get(ctx).snapshot().total)
        TypingStats.reload()
    }
}
