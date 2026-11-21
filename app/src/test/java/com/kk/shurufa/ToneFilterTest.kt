package com.kk.shurufa

import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.PinyinTones
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 声调筛选: which tones a character has for the syllable typed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToneFilterTest {
    private val t = PinyinTones(ApplicationProvider.getApplicationContext())

    @Test fun tonesFollowTheTypedSyllable() {
        assertEquals(setOf(2), t.tonesOf('长', "chang"))
        assertEquals(setOf(3), t.tonesOf('长', "zhang"))
        assertEquals(setOf(3), t.tonesOf('你', "ni"))
        assertEquals(setOf(1), t.tonesOf('妈', "ma"))
        assertEquals(setOf(4), t.tonesOf('骂', "ma"))
        assertEquals(setOf(3, 4), t.tonesOf('好', "hao"))
        assertEquals(setOf(4), t.tonesOf('绿', "lv"))
        // an abbreviation (only the initial typed) still finds the readings
        assertEquals(setOf(3), t.tonesOf('你', "n"))
    }
}
