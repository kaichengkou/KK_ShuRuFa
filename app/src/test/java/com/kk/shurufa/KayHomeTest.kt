package com.kk.shurufa

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import com.kk.shurufa.keyboard.KayHome
import com.kk.shurufa.keyboard.KbTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** 小K's page in motion: poked it moves and comes back; a joke's answer goes on after its set-up. Frames to build/screens/kay_home_frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KayHomeTest {
    @Test fun alive() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = Prefs(act).apply { put(Prefs.ANIMATIONS, true) }
        val home = KayHome(act, prefs).apply { theme = KbTheme.resolve(act, prefs); line = "早，水温刚好 (・ω・)ﾉ"; provider = "Gemini 3.5 Flash Lite"; place = "🇯🇵 日本"; placeOk = true }
        val d = act.resources.displayMetrics.density
        val w = act.resources.displayMetrics.widthPixels; val h = (250 * d).toInt()
        act.setContentView(FrameLayout(act).apply { addView(home, FrameLayout.LayoutParams(w, h)) })
        shadowOf(Looper.getMainLooper()).idle()
        home.entered()
        val dir = File("build/screens/kay_home_frames").apply { deleteRecursively(); mkdirs() }
        var n = 0
        fun field(name: String) = KayHome::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun step(ms: Long, every: Long = 66) {
            var k = 0L
            while (k < ms) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(every)); k += every
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); home.draw(Canvas(b))
                File(dir, "%04d.png".format(n++)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        step(2200)
        // poked a few times: it moves, answers, and is back in its place a while later
        val sx = w * 0.25f; val sy = h * 0.72f
        repeat(3) {
            val t0 = android.os.SystemClock.uptimeMillis()
            home.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, sx, sy, 0))
            home.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 60, MotionEvent.ACTION_UP, sx, sy, 0))
            step(1500)
        }
        step(5000)
        val px = field("posX").get(home) as com.kk.shurufa.keyboard.Tween
        assertEquals("back in its place", 0f, px.value, 0.5f)
        // left alone: threads, then jokes; a joke's answer is typed after its set-up, not again from the start
        field("threads").setInt(home, 3); field("thread").set(home, emptyList<String>())
        step(5000)
        assertTrue("jokes now", field("joking").getBoolean(home))
        step(9000)
        val bubble = field("bubble").get(home) as String
        if (bubble.contains("——")) assertEquals(bubble.substringBefore("\n——").length, field("bubbleFrom").getInt(home))
    }
}
