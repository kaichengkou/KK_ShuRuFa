package com.kk.shurufa

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.keyboard.TabStrip
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The clipboard's filter row: tabs as wide as their names, the rest of the row is empty. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TabStripTest {
    @Test fun emptyRestOfTheRowIsNotATab() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val picked = mutableListOf<String>()
        val strip = TabStrip(ctx, Prefs(ctx)).apply {
            compact = true
            setTabs(listOf("" to "全部", "code" to "验证码", "link" to "链接"), "")
            onTab = { picked += it }
        }
        val w = ctx.resources.displayMetrics.widthPixels
        strip.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        strip.layout(0, 0, w, strip.measuredHeight)
        fun tap(x: Float) {
            val t = SystemClock.uptimeMillis()
            strip.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, strip.height / 2f, 0))
            strip.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, strip.height / 2f, 0))
        }
        tap(w * 0.85f)                // the empty right side
        assertEquals(emptyList<String>(), picked)
        tap(w * 0.2f)                 // on 验证码
        assertEquals(listOf("code"), picked)
    }
}
