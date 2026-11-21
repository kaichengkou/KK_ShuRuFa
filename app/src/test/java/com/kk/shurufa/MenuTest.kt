package com.kk.shurufa

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PopupOverlay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Long-press a candidate, lift, then tap an option of the menu that stays open. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MenuTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val events = mutableListOf<String>()

    private fun ev(v: View, a: Int, x: Float, y: Float) { val t = SystemClock.uptimeMillis(); v.dispatchTouchEvent(MotionEvent.obtain(t, t, a, x, y, 0)) }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun candidateMenuStaysAndTakesATap() {
        val prefs = Prefs(ctx); prefs.put(Prefs.ANIMATIONS, false)
        val root = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val overlay = PopupOverlay(ctx, prefs)
        val bar = CandidateBar(ctx, prefs)
        bar.overlay = overlay
        bar.listener = object : CandidateBar.Listener {
            override fun onTool(id: String) {}
            override fun onCandidate(index: Int) { events += "cand:$index" }
            override fun onExpand() {}
            override fun onMathFormat(format: String) {}
            override fun onResultChip(text: String) {}
            override fun candidateOptions(index: Int) = listOf(Output("取消", "§cancel"), Output("删除学习记录", "§forget"))
            override fun onCandidateOption(index: Int, option: Output) { events += "opt:$index:${option.text}" }
        }
        col.addView(bar); col.addView(View(ctx), LinearLayout.LayoutParams(-1, 600))
        root.addView(col); root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val w = ctx.resources.displayMetrics.widthPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, 1200)
        bar.showCandidates("ni hao", listOf("你好", "拟好", "你"), emptyList())
        root.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(w, 1200, android.graphics.Bitmap.Config.ARGB_8888)))
        val y = bar.height * 0.7f
        // long-press the second candidate; the finger trembles a little, as on a real screen, then lifts
        ev(root, MotionEvent.ACTION_DOWN, 200f, y); idle(600)
        ev(root, MotionEvent.ACTION_MOVE, 203f, y + 2f); ev(root, MotionEvent.ACTION_MOVE, 199f, y - 3f)
        ev(root, MotionEvent.ACTION_UP, 199f, y - 3f)
        idle(300)
        assertEquals(events.toString(), true, overlay.sticky)
        assertEquals(events.toString(), true, events.none { it.startsWith("cand:") })
        // the menu is below the candidate; tap its second option (rows run outwards from the key)
        val f = PopupOverlay::class.java.getDeclaredField("opRect").apply { isAccessible = true }
        val r = f.get(overlay) as android.graphics.RectF
        println("menu at $r")
        ev(root, MotionEvent.ACTION_DOWN, r.right - 30f, r.centerY()); ev(root, MotionEvent.ACTION_UP, r.right - 30f, r.centerY())
        idle(300)
        println("events=$events")
        assertEquals(false, overlay.sticky)
        assertEquals(true, events.any { it.startsWith("opt:") })
    }
}
