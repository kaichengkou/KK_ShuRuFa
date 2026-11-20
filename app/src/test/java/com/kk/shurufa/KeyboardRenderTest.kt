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
        override fun onBackspaceGesture(action: String) { events += "bs:$action" }
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

        // long-press and release without moving picks the first option (the up-flick)
        touch(MotionEvent.ACTION_DOWN, ax, ay)
        idle(400)
        touch(MotionEvent.ACTION_MOVE, ax + 6, ay - 4)
        touch(MotionEvent.ACTION_UP, ax + 6, ay - 4)

        // swipe space left → next language
        val (sx, sy) = keyCenter { it.fn == "space" }
        touch(MotionEvent.ACTION_DOWN, sx, sy); touch(MotionEvent.ACTION_MOVE, sx - 60, sy); touch(MotionEvent.ACTION_MOVE, sx - 200, sy); touch(MotionEvent.ACTION_UP, sx - 200, sy)

        // long-press space, then drag to move the cursor
        touch(MotionEvent.ACTION_DOWN, sx, sy)
        idle(400)
        shot("zh_cursor_mode")
        touch(MotionEvent.ACTION_MOVE, sx + 100, sy)
        touch(MotionEvent.ACTION_UP, sx + 100, sy)

        // swipe up on backspace clears, swipe down undoes
        val (bx, by) = keyCenter { it.fn == "backspace" }
        touch(MotionEvent.ACTION_DOWN, bx, by); touch(MotionEvent.ACTION_MOVE, bx, by - 50); touch(MotionEvent.ACTION_MOVE, bx, by - 120)
        shot("zh_backspace_clear")
        touch(MotionEvent.ACTION_UP, bx, by - 120)
        touch(MotionEvent.ACTION_DOWN, bx, by); touch(MotionEvent.ACTION_MOVE, bx, by + 50); touch(MotionEvent.ACTION_MOVE, bx, by + 120); touch(MotionEvent.ACTION_UP, bx, by + 120)

        println(events.joinToString(" | "))
        assertEquals("out:q", events[0])
        assertEquals("out:1", events[1])
        assertEquals("out:（", events[2])
        assertTrue(events.any { it.startsWith("opt:") && it != "opt:～" })
        assertTrue("opt:～" in events)
        assertTrue("lang:1" in events)
        assertTrue("curmode:true" in events)
        assertTrue(events.any { it.startsWith("cur:") && it.split(":")[1].split(",")[0].toInt() > 0 })
        assertTrue("bs:clear" in events)
        assertTrue("bs:undo" in events)

        bar.showCandidates("ni hap", listOf("你好", "你受", "你搞", "拟好", "龘", "👋", "泥"), listOf("p→o", "", "h→g", "p→o", "dá", "", ""))
        kb.setRows(repo.keyboard("ru").rows); layoutRoot(); shot("ru")
        bar.showToolbar()
        kb.setRows(repo.keyboard("en").rows); layoutRoot(); shot("en")
        val m = repo.math()
        bar.showMath(m.tabs.map { it.id to it.name }, "basic", "uni")
        kb.setRows(m.tabs[0].rows + listOf(m.bottom), math = true); layoutRoot(); shot("math_basic")
        kb.setRows(m.tabs[2].rows + listOf(m.bottom), math = true); layoutRoot(); shot("math_calculus")
        bar.showMath(m.tabs.map { it.id to it.name }, "phys", "tex")
        kb.setRows(m.tabs[6].rows + listOf(m.bottom), math = true); layoutRoot(); shot("math_physics")
        bar.showPredictions(listOf("人民", "经济", "政府", "市场", "企业", "特色"))
        kb.setRows(repo.keyboard("zh").rows, math = false); layoutRoot(); shot("zh_predictions")
        bar.showClip("https://github.com/kaichengkou/KK_ShuRuFa", "链接")
        layoutRoot(); shot("zh_clip_chip")
    }

    private fun panelShot(name: String, v: View, setup: ((CandidateBar) -> Unit)? = null) {
        val w = ctx.resources.displayMetrics.widthPixels
        val h = (ctx.resources.displayMetrics.density * 220).toInt()
        val frame = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val b = CandidateBar(ctx, prefs)
        b.activeTool = when (name) { "quick" -> "quick"; "cursor" -> "cursor"; "clipboard" -> "clipboard"; "emoji" -> "emoji"; else -> null }
        if (name == "num") b.showMath(emptyList(), "", "uni", simple = true)
        setup?.invoke(b)
        col.addView(b)
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        col.addView(v, LinearLayout.LayoutParams(-1, h))
        frame.addView(col)
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bmp))
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderPanels() {
        val repo = LayoutRepo(ctx)
        // !#1 number page
        val num = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val strip = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 1; rowHeightDp = 44f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.SOFT }
        val keys = KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener; setRows(repo.keyboard("num").rows) }
        num.addView(strip, LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 66).toInt(), -1))
        num.addView(keys, LinearLayout.LayoutParams(0, -1, 1f))
        strip.setItems("+ − × ÷ = ( ) % ， 。 ？".split(" "))
        panelShot("num", num)
        // cursor panel
        val cur = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cur.addView(com.kk.shurufa.keyboard.DpadView(ctx, prefs), LinearLayout.LayoutParams(0, -1, 1f))
        cur.addView(KeyboardView(ctx, prefs).apply { iconLabels = "icon"; listener = this@KeyboardRenderTest.listener; setRows(repo.keyboard("cursor").rows) }, LinearLayout.LayoutParams(0, -1, 1.1f))
        panelShot("cursor", cur)
        // quick panel
        val q = com.kk.shurufa.keyboard.QuickPanel(ctx, prefs)
        q.tiles = listOf(
            com.kk.shurufa.keyboard.QuickPanel.Tile("trad", "繁体", "简繁切换", false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("emoji", "Emoji", "候选里带表情", true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("predict", "联想", "上屏后推荐下文", true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("correct", "纠错", "拼音按键纠错", true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("pinyin", "拼音模式", "上屏带声调拼音", false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("stress", "重音模式", "俄语元音上滑出重音", false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("latex", "LaTeX", "数学键盘输出", false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("theme", "主题", "跟随系统"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("vibrate", "振动", "", true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("sound", "按键音", "", false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("math", "数学键盘"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("symbols", "全部符号"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("cursor", "光标"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("clipboard", "剪贴板"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("picker", "切换输入法"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("settings", "完整设置"),
        )
        panelShot("quick", q)
        // expanded candidates
        val cand = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.FLAT; columns = 6; rowHeightDp = 50f; highlightFirst = true }
        cand.addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
        cand.addView(KeyboardView(ctx, prefs).apply { heightScale = 0.9f; listener = this@KeyboardRenderTest.listener
            setRows(listOf(listOf(Key(fn = "collapse", label = "收起")), listOf(Key(fn = "tall", label = "拉高")), listOf(Key(fn = "backspace")), listOf(Key(fn = "reinput", label = "重输")))) },
            LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 68).toInt(), -1))
        panelShot("candidates", cand)
        grid.setItems(listOf("你好", "拟好", "你好啊朋友", "你好吗", "👋", "你还", "你", "尼", "泥", "逆", "拟", "倪", "妮", "腻", "匿", "霓", "昵", "旎", "睨", "伲"))
        panelShot("candidates", cand)
        // clipboard card stack
        val clip = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = KeyboardView(ctx, prefs).apply { heightScale = 0.78f; listener = this@KeyboardRenderTest.listener
            setRows(listOf(listOf(Key(fn = "clip_recent", label = "最近 6", width = 1.6f, style = com.kk.shurufa.keyboard.KeyStyle.ACTION),
                Key(fn = "clip_fav", label = "收藏 2", width = 1.6f), Key(fn = "tall", label = "拉高", width = 1.3f), Key(fn = "clear_clips", label = "清空最近", width = 1.3f)))) }
        val stack = com.kk.shurufa.keyboard.CardStackView(ctx, prefs)
        clip.addView(header, LinearLayout.LayoutParams(-1, -2))
        clip.addView(stack, LinearLayout.LayoutParams(-1, 0, 1f))
        panelShot("clipboard", clip)
        stack.setCards(listOf(
            com.kk.shurufa.keyboard.CardStackView.Card("明天下午三点在学校门口见，别忘了带材料。路上小心，到了给我发消息。", "", false),
            com.kk.shurufa.keyboard.CardStackView.Card("莫斯科市特维尔大街 7 号", "", true),
            com.kk.shurufa.keyboard.CardStackView.Card("https://github.com/kaichengkou/KK_ShuRuFa", "链接", false),
            com.kk.shurufa.keyboard.CardStackView.Card("482913", "验证码", false),
        ), keepPosition = false)
        panelShot("clipboard", clip)
        // emoji
        val emo = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val erail = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 1; rowHeightDp = 36f; textSp = 13.5f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.RAIL; selectedIndex = 1 }
        val ecells = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 7; rowHeightDp = 46f; textSp = 25f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.PLAIN }
        emo.addView(erail, LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 64).toInt(), -1))
        emo.addView(ecells, LinearLayout.LayoutParams(0, -1, 1f))
        panelShot("emoji", emo)
        val groups = ctx.assets.open("data/emoji.txt").bufferedReader().readLines().map { it.substringBefore('\t') to it.substringAfter('\t').split(' ') }
        erail.setItems(listOf("最近") + groups.map { it.first })
        ecells.setItems(groups[0].second)
        panelShot("emoji", emo)
        // scientific number keyboard bar
        val m = repo.math()
        val sci = KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener; setRows(m.tabs[2].rows + listOf(m.bottom), math = true) }
        panelShot("sci", sci) { it.showMath(m.tabs.map { t -> t.id to t.name }, "calc", "uni", simple = false) }
        // symbols
        val sym = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val rail = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 1; rowHeightDp = 38f; textSp = 14f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.RAIL; selectedIndex = 1 }
        val cells = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.SOFT }
        sym.addView(rail, LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 76).toInt(), -1))
        sym.addView(cells, LinearLayout.LayoutParams(0, -1, 1f))
        panelShot("symbols", sym)
        rail.setItems(repo.symbols().map { it.name })
        cells.setItems(repo.symbols()[1].items.map { it.label })
        panelShot("symbols", sym)
    }
}
