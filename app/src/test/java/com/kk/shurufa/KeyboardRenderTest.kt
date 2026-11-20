package com.kk.shurufa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyboardListener
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PopupOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File
import java.time.Duration

/** Renders the keyboard to PNGs (build/screens) and drives the gestures with synthetic touches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardRenderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var kb: KeyboardView
    private lateinit var bar: CandidateBar
    private lateinit var overlay: PopupOverlay
    private val events = mutableListOf<String>()
    private var shift = 0
    private var spaceLabel = "中文"

    private val listener = object : KeyboardListener {
        override fun onOutput(key: Key, output: Output) { events += "out:${output.text}" }
        override fun onFunction(key: Key) { events += "fn:${key.fn}" }
        override fun longOptions(key: Key): List<Output> =
            if (key.main?.text == "a") listOf(Output.of("～"), Output.of("A"), Output.of("a"), Output.of("á")) else emptyList()
        override fun onOption(key: Key, option: Output) { events += "opt:${option.text}" }
        override fun onCursorMove(dx: Int, dy: Int) { events += "cur:$dx,$dy" }
        override fun onCursorMode(active: Boolean) { events += "curmode:$active" }
        override fun onSwitchLanguage(direction: Int): String { events += "lang:$direction"; spaceLabel = "Русский"; return spaceLabel }
        override fun onBackspaceRepeat() { events += "bsrep" }
        override fun onBackspaceSwipe(words: Int, commit: Boolean) { events += "bsswipe:$words:$commit" }
        override fun onPress(key: Key) {}
        override fun shiftState() = shift
        override fun spaceLabel() = spaceLabel
    }

    @Before fun setUp() {
        prefs = Prefs(ctx)
        prefs.put(Prefs.ANIMATIONS, false)
        root = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bar = CandidateBar(ctx, prefs)
        overlay = PopupOverlay(ctx, prefs)
        kb = KeyboardView(ctx, prefs).also { it.listener = listener; it.overlay = overlay }
        col.addView(bar); col.addView(kb)
        root.addView(col); root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun layoutRoot() {
        val w = ctx.resources.displayMetrics.widthPixels
        // the input method window offers the whole screen height as AT_MOST, like on the phone
        val screenH = ctx.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST))
        assertTrue("input view must not fill the screen: ${root.measuredHeight} of $screenH", root.measuredHeight < screenH / 2)
        assertEquals(root.measuredHeight, overlay.measuredHeight)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun shot(name: String) {
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun keyCenter(pred: (Key) -> Boolean): Pair<Float, Float> {
        val f = KeyboardView::class.java.getDeclaredField("rects").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rects = f.get(kb) as List<Pair<Key, android.graphics.RectF>>
        val r = rects.first { pred(it.first) }.second
        return r.centerX() to r.centerY()
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        kb.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun renderAndGestures() {
        val repo = LayoutRepo(ctx)
        kb.setRows(repo.keyboard("zh").rows)
        layoutRoot()
        shot("zh")

        // tap q, flick q up
        val (qx, qy) = keyCenter { it.main?.text == "q" }
        touch(MotionEvent.ACTION_DOWN, qx, qy); touch(MotionEvent.ACTION_UP, qx, qy)
        touch(MotionEvent.ACTION_DOWN, qx, qy); touch(MotionEvent.ACTION_MOVE, qx, qy - 80); touch(MotionEvent.ACTION_UP, qx, qy - 80)
        // flick z up → （
        val (zx, zy) = keyCenter { it.main?.text == "z" }
        touch(MotionEvent.ACTION_DOWN, zx, zy); touch(MotionEvent.ACTION_MOVE, zx, zy - 80)
        shot("zh_flick")
        touch(MotionEvent.ACTION_UP, zx, zy - 80)

        // long-press a, drag one cell to the right, release
        val (ax, ay) = keyCenter { it.main?.text == "a" }
        touch(MotionEvent.ACTION_DOWN, ax, ay)
        idle(400)
        touch(MotionEvent.ACTION_MOVE, ax + 1, ay)
        shot("zh_longpress")
        touch(MotionEvent.ACTION_MOVE, ax + 120, ay - 140)
        touch(MotionEvent.ACTION_UP, ax + 120, ay - 140)

        // swipe space left → next language
        val (sx, sy) = keyCenter { it.fn == "space" }
        touch(MotionEvent.ACTION_DOWN, sx, sy); touch(MotionEvent.ACTION_MOVE, sx - 60, sy); touch(MotionEvent.ACTION_MOVE, sx - 200, sy); touch(MotionEvent.ACTION_UP, sx - 200, sy)

        // long-press space, then drag to move the cursor
        touch(MotionEvent.ACTION_DOWN, sx, sy)
        idle(400)
        shot("zh_cursor_mode")
        touch(MotionEvent.ACTION_MOVE, sx + 100, sy)
        touch(MotionEvent.ACTION_UP, sx + 100, sy)

        // swipe left on backspace
        val (bx, by) = keyCenter { it.fn == "backspace" }
        touch(MotionEvent.ACTION_DOWN, bx, by); touch(MotionEvent.ACTION_MOVE, bx - 80, by); touch(MotionEvent.ACTION_MOVE, bx - 200, by); touch(MotionEvent.ACTION_UP, bx - 200, by)

        println(events.joinToString(" | "))
        assertEquals("out:q", events[0])
        assertEquals("out:1", events[1])
        assertEquals("out:（", events[2])
        assertTrue(events.any { it.startsWith("opt:") && it != "opt:～" })
        assertTrue("lang:1" in events)
        assertTrue("curmode:true" in events)
        assertTrue(events.any { it.startsWith("cur:") && it.split(":")[1].split(",")[0].toInt() > 0 })
        assertTrue(events.any { it.startsWith("bsswipe:") && it.endsWith(":true") && it.split(":")[1].toInt() > 0 })

        bar.showCandidates("ni hao", listOf("你好", "👋", "拟好", "你", "尼", "泥", "逆"), emptyList())
        kb.setRows(repo.keyboard("ru").rows); layoutRoot(); shot("ru")
        bar.showToolbar()
        kb.setRows(repo.keyboard("en").rows); layoutRoot(); shot("en")
        val m = repo.math()
        bar.showMath(m.tabs.map { it.id to it.name }, "basic", "uni")
        kb.setRows(m.tabs[0].rows + listOf(m.bottom), math = true); layoutRoot(); shot("math_basic")
        kb.setRows(m.tabs[2].rows + listOf(m.bottom), math = true); layoutRoot(); shot("math_calculus")
        bar.showMath(m.tabs.map { it.id to it.name }, "phys", "tex")
        kb.setRows(m.tabs[6].rows + listOf(m.bottom), math = true); layoutRoot(); shot("math_physics")
        kb.setRows(repo.keyboard("cursor").rows); layoutRoot(); shot("cursor")
    }
}
