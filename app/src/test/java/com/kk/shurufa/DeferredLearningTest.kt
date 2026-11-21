package com.kk.shurufa

import android.os.Looper
import com.kk.shurufa.ime.DeferredLearning
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 学习延后: a word deleted right after it was committed is not learned. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeferredLearningTest {
    @Test fun deletedSlipsAreDropped() {
        val l = DeferredLearning()
        var field = ""
        fun commit(py: String, t: String) { l.committed(py, t); field += t }
        fun backspace() { l.deleting { n -> field.takeLast(n) }; field = field.dropLast(1) }
        commit("jintian", "今天"); commit("qushi", "去世")      // meant 去市 … deleted at once
        backspace()
        assertEquals("the slip is dropped, the word before stays", 1, l.pending)
        backspace()
        assertEquals(1, l.pending)
        // punctuation typed after a word, then deleted: the word itself is still kept
        field += "，"; backspace()
        assertEquals(1, l.pending)
        // after the delay it is taught (and leaves the queue)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DeferredLearning.DELAY_MS + 100))
        assertEquals(0, l.pending)
        // 撤销 and 上滑清空
        commit("nihao", "你好"); l.undone(); assertEquals(0, l.pending)
        commit("nihao", "你好"); commit("shijie", "世界"); l.clearedAll(); assertEquals(0, l.pending)
    }
}
