package com.kk.shurufa

import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.ZhMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/** 联想记忆: what was written after what comes first, by the longest context; one-off noise does not. */
@RunWith(RobolectricTestRunner::class)
class ZhMemoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun remembersWhatFollows() {
        ZhMemory.clearAll(ctx)
        val m = ZhMemory(ctx)
        m.learn("我们明天", "去爬山")
        assertEquals("去爬山", m.suggest("下周我们明天").first())
        // one character of context alone needs to have been seen twice
        assertTrue(m.suggest("后天").isEmpty())
        m.learn("后天", "开会"); assertTrue(m.suggest("天").isEmpty())
        m.learn("前天", "开会")
        assertEquals(listOf("开会"), m.suggest("明天").filter { it == "开会" })
        // the longer context wins over the shorter one
        m.learn("你明天", "上班"); m.learn("你明天", "上班")
        assertEquals("去爬山", m.suggest("我们明天").first())
        assertEquals("上班", m.suggest("你明天").first())
        // not after punctuation, nothing too long, nothing that is not Chinese
        assertTrue(m.suggest("明天。").isEmpty())
        m.learn("今天", "我们一起去学校图书馆"); assertTrue("我们一起去学校图书馆" !in m.suggest("今天"))
    }

    @Test fun keptAcrossRestarts() {
        ZhMemory.clearAll(ctx)
        val m = ZhMemory(ctx)
        m.learn("项目", "进展顺利")
        m.save()
        shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(300)
        val m2 = ZhMemory(ctx); m2.load()
        Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("进展顺利", m2.suggest("这个项目").firstOrNull())
        ZhMemory.clearAll(ctx)
        assertTrue(m2.suggest("这个项目").isEmpty())
    }
}
