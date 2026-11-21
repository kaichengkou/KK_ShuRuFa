package com.kk.shurufa

import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.online.AiBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard's own pace with AI services while typing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiBudgetTest {
    @Test fun keepsUnderTheFreeTierAndWaitsWhenTold() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        val before = AiBudget.today(prefs)
        var t = 10_000_000L
        repeat(AiBudget.PER_MINUTE) { assertTrue(AiBudget.mayAsk(t)); AiBudget.asked(prefs, t); t += 1000 }
        assertFalse("no more this minute", AiBudget.mayAsk(t))
        assertTrue("a minute later again", AiBudget.mayAsk(t + 61_000))
        assertEquals(before + AiBudget.PER_MINUTE, AiBudget.today(prefs))
        t += 200_000
        // told to wait: a short pause, then a long one when it keeps happening
        assertFalse(AiBudget.limited(t)); assertFalse(AiBudget.mayAsk(t + 10_000)); assertTrue(AiBudget.mayAsk(t + AiBudget.SHORT_PAUSE_MS + 1))
        assertFalse(AiBudget.limited(t)); assertTrue(AiBudget.limited(t))
        assertFalse(AiBudget.mayAsk(t + AiBudget.SHORT_PAUSE_MS + 1)); assertTrue(AiBudget.mayAsk(t + AiBudget.LONG_PAUSE_MS + 1))
        assertTrue(AiBudget.justPaused)
    }
}
