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
        override fun longOptionRows(key: Key): List<List<Output>> =
            if (key.main?.text == "l") listOf(listOf(Output.of("？"), Output.of("?"), Output.of("¿")), listOf(Output.of("L"), Output.of("l"), Output.of("ł")))
            else listOf(longOptions(key))
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
        val r = kb.keyRect(pred)!!
        return r.centerX() to r.centerY()
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        kb.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /**
     * 落点自适应: a tap just inside q, next to w, reads as q until the keyboard has seen that this
     * person's taps on w land on its left side; then the edge between them follows those taps.
     * A tap undone by backspace teaches nothing.
     */
    @Test fun learnsWhereTapsLand() {
        kb.setRows(LayoutRepo(ctx).keyboard("zh").rows)
        layoutRoot()
        val q = android.graphics.RectF(kb.keyRect { it.main?.text == "q" }!!)
        val w = android.graphics.RectF(kb.keyRect { it.main?.text == "w" }!!)
        fun tap(x: Float, y: Float): String {
            events.clear()
            touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y)
            return events.firstOrNull { it.startsWith("out:") }?.removePrefix("out:") ?: events.joinToString()
        }
        val edgeX = q.right - q.width() * 0.05f
        val y = q.centerY()
        val model = com.kk.shurufa.keyboard.TouchModel(prefs.state)
        kb.touch = model
        assertEquals("before anything is learned, the key shapes decide", "q", tap(edgeX, y))
        val bs = kb.keyRect { it.fn == "backspace" }!!
        // mistakes: w tapped far right, each undone at once; they must not count
        repeat(30) { tap(w.right - 2f, y); tap(bs.centerX(), bs.centerY()) }
        assertEquals("q", tap(edgeX, y))
        // this person hits w on its left side, and keeps what they typed
        repeat(60) { tap(w.left + w.width() * 0.12f, y); tap(kb.keyRect { it.main?.text == "e" }!!.centerX(), y) }
        assertEquals("w", tap(edgeX, y))
        assertEquals("the middle of q stays q", "q", tap(q.centerX(), y))
        // what was learned is kept, and a new model reads it back
        model.save(); idle(50); Thread.sleep(100); idle(50)
        kb.touch = com.kk.shurufa.keyboard.TouchModel(prefs.state)
        assertEquals("w", tap(edgeX, y))
        // 清除 in the settings forgets it at once, also in the model in use
        prefs.state.edit().remove(com.kk.shurufa.keyboard.TouchModel.KEY).apply()
        assertEquals("q", tap(edgeX, y))
        kb.touch = null
    }

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

        // on the right half the options run leftwards: one cell left is the second symbol,
        // one row up is the letters
        val (lx, ly) = keyCenter { it.main?.text == "l" }
        touch(MotionEvent.ACTION_DOWN, lx, ly)
        idle(400)
        touch(MotionEvent.ACTION_MOVE, lx + 1, ly)
        shot("zh_longpress_right")
        val cell = maxOf(ctx.resources.displayMetrics.density * 40f, 0f)
        touch(MotionEvent.ACTION_MOVE, lx - cell * 1.1f, ly - 8)
        touch(MotionEvent.ACTION_UP, lx - cell * 1.1f, ly - 8)
        assertTrue(events.toString(), "opt:?" in events)

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
        kb.setRows(repo.keyboard("zh").rows); layoutRoot(); shot("zh_notes")
        bar.showEdit("nihapma", 4, setOf(2, 5)); layoutRoot(); shot("zh_pinyin_edit")
        bar.showCandidates("ni hap", listOf("你好", "你受", "你搞", "拟好", "龘", "👋", "泥"), listOf("p→o", "", "h→g", "p→o", "dá", "", ""))
        kb.setRows(repo.keyboard("ru").rows); layoutRoot(); shot("ru")
        bar.showToolbar()
        kb.setRows(repo.keyboard("en").rows); layoutRoot(); shot("en")
        val m = repo.math()
        bar.showMath("uni", simple = false)
        bar.showPredictions(listOf("人民", "经济", "政府", "市场", "企业", "特色"))
        kb.setRows(repo.keyboard("zh").rows, math = false); layoutRoot(); shot("zh_predictions")
        bar.showClip("https://github.com/kaichengkou/KK_ShuRuFa", "链接")
        layoutRoot(); shot("zh_clip_chip")
    }

    private fun panelShot(name: String, v: View, heightDp: Int = 220, noBar: Boolean = false, setup: ((CandidateBar) -> Unit)? = null) {
        val w = ctx.resources.displayMetrics.widthPixels
        val h = (ctx.resources.displayMetrics.density * heightDp).toInt()
        val frame = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val b = CandidateBar(ctx, prefs)
        b.activeTool = when (name) { "quick" -> "quick"; "clipboard" -> "clipboard"; "emoji" -> "emoji"; else -> null }
        setup?.invoke(b)
        if (!noBar) col.addView(b)
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        col.addView(v, LinearLayout.LayoutParams(-1, h))
        frame.addView(col)
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bmp))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Like panelShot, at the view's own height. */
    private fun panelShotWrap(name: String, v: View, noBar: Boolean = false, setup: ((CandidateBar) -> Unit)? = null) {
        val w = ctx.resources.displayMetrics.widthPixels
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panelShot(name, v, (v.measuredHeight / ctx.resources.displayMetrics.density).toInt() + 1, noBar = noBar, setup = setup)
    }

    @Test fun renderPanels() {
        val repo = LayoutRepo(ctx)
        val fam = 220
        // 数字·标准: keys with the operator column over their spacers
        val opStrip = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply {
            cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.SOFT; columns = 1; textSp = 20f; cellPadDp = 0f; cellGapDp = prefs.keyGapDp }
        val num = com.kk.shurufa.keyboard.NumPadView(ctx, KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener; setRows(repo.keyboard("num").rows) }, opStrip)
        num.setItems(listOf("+", "−", "×", "÷", "=", "%", "(", ")", "/", ",", "±"))
        panelShot("num", num, fam) { it.showMath("uni", simple = true) }
        // 数字·学术
        val m = repo.math()
        val sci = com.kk.shurufa.keyboard.MathPanel(ctx, prefs) { KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener } }
        for ((i, t) in m.tabs.withIndex()) {
            sci.show(m, t.id)
            panelShotWrap("sci_${i}_${t.id}", sci) { it.showMath(if (t.id == "lin") "tex" else "uni", simple = false) }
        }
        // symbols: no toolbar, so the panel takes its height
        val sym = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val symTop = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val rail = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 1; rowHeightDp = 36f; textSp = 13.5f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.RAIL; selectedIndex = 0 }
        val cells = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.SOFT; columns = 6; rowHeightDp = 42f; textSp = 18.5f; emptyText = "用过的符号会出现在这里" }
        symTop.addView(rail, LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 60).toInt(), -1))
        symTop.addView(cells, LinearLayout.LayoutParams(0, -1, 1f))
        sym.addView(symTop, LinearLayout.LayoutParams(-1, 0, 1f))
        sym.addView(KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener; setRows(listOf(com.kk.shurufa.keyboard.FamilyRows.symbols())) }, LinearLayout.LayoutParams(-1, -2))
        rail.setItems(listOf("最近") + repo.symbols().map { it.name })
        cells.setItems(emptyList())
        panelShot("symbols_recent_empty", sym, fam + 50, noBar = true)
        rail.selectedIndex = 2
        cells.setItems(repo.symbols()[1].items.map { it.label })
        panelShot("symbols", sym, fam + 50, noBar = true)
        // 文字编辑
        val edit = com.kk.shurufa.keyboard.EditPanel(ctx, prefs)
        panelShot("cursor", edit, 220) { it.activeTool = "cursor" }
        edit.selecting = true; edit.hasSelection = true
        panelShot("cursor_selecting", edit, 220) { it.activeTool = "cursor" }
        // quick panel
        val q = com.kk.shurufa.keyboard.QuickPanel(ctx, prefs)
        q.tiles = listOf(
            com.kk.shurufa.keyboard.QuickPanel.Tile("predict", "联想", on = true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("correct", "纠错", on = true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("trad", "繁体", on = false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("emoji", "候选表情", on = true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("pinyin", "拼音模式", on = false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("stress", "俄语重音", on = false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("vibrate", "振动", on = true),
            com.kk.shurufa.keyboard.QuickPanel.Tile("sound", "按键音", on = false),
            com.kk.shurufa.keyboard.QuickPanel.Tile("theme", "主题", "自动"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("toolbar", "工具栏"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("picker", "切换输入法"),
            com.kk.shurufa.keyboard.QuickPanel.Tile("settings", "设置"),
        )
        panelShot("quick", q)
        // expanded candidates: 8 units, single characters take one, notes above
        val cand = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.FLAT; columns = 8; rowHeightDp = 50f; highlightFirst = true }
        cand.addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
        cand.addView(KeyboardView(ctx, prefs).apply { heightScale = 0.9f; listener = this@KeyboardRenderTest.listener
            setRows(listOf(listOf(Key(fn = "collapse", label = "收起")), listOf(Key(fn = "backspace")), listOf(Key(fn = "reinput", label = "重输")))) },
            LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 68).toInt(), -1))
        val words = listOf("你好", "拟好", "你好啊朋友", "你好吗", "👋", "你还", "你", "尼", "泥", "逆", "拟", "倪", "妮", "腻", "匿", "霓", "昵", "旎", "睨", "伲", "嶷", "猊")
        grid.notes = words.map { w -> when (w) { "拟好" -> "p→o"; "旎" -> "nǐ"; "睨" -> "nì"; "伲" -> "nǐ"; "嶷" -> "nì"; "猊" -> "ní"; else -> "" } }
        grid.setItems(words)
        panelShot("candidates", cand)
        // clipboard: recent strip above, favorites below
        val clip = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val handle = com.kk.shurufa.keyboard.SheetHandle(ctx, prefs)
        val strip = com.kk.shurufa.keyboard.ClipStrip(ctx, prefs)
        val favs = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.CARD; columns = 2; rowHeightDp = 66f; textSp = 13.5f; header = "收藏 3" }
        clip.addView(handle, LinearLayout.LayoutParams(-1, -2))
        clip.addView(strip, LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.density * 118).toInt()))
        clip.addView(favs, LinearLayout.LayoutParams(-1, 0, 1f))
        strip.setCards(listOf(
            com.kk.shurufa.keyboard.ClipStrip.Card("好的", "", false),
            com.kk.shurufa.keyboard.ClipStrip.Card("482913", "验证码", false),
            com.kk.shurufa.keyboard.ClipStrip.Card("明天下午三点在学校门口见，别忘了带材料。路上小心，到了给我发消息。", "", false),
            com.kk.shurufa.keyboard.ClipStrip.Card("莫斯科市特维尔大街 7 号", "", true),
            com.kk.shurufa.keyboard.ClipStrip.Card("https://github.com/kaichengkou/KK_ShuRuFa", "链接", false),
        ), keepPosition = false)
        favs.tags = listOf("", "邮箱", "")
        favs.setItems(listOf("莫斯科市特维尔大街 7 号", "someone@example.com", "Спасибо, увидимся завтра!"))
        panelShot("clipboard", clip, 240)
        // emoji
        val emo = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val erail = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 1; rowHeightDp = 36f; textSp = 13.5f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.RAIL; selectedIndex = 1 }
        val ecells = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 8; rowHeightDp = 40f; textSp = 22f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.PLAIN }
        emo.addView(erail, LinearLayout.LayoutParams((ctx.resources.displayMetrics.density * 64).toInt(), -1))
        emo.addView(ecells, LinearLayout.LayoutParams(0, -1, 1f))
        val groups = ctx.assets.open("data/emoji.txt").bufferedReader().readLines().map { it.substringBefore('\t') to it.substringAfter('\t').split(' ') }
        erail.railIcons = listOf("search")
        erail.setItems(listOf("查找", "最近") + groups.map { it.first } + "颜文字")
        erail.selectedIndex = 2
        ecells.setItems(groups[1].second)
        val vmap = com.kk.shurufa.ime.EmojiData(ctx).apply { load() }.variants
        ecells.marks = groups[1].second.indices.filter { vmap.containsKey(groups[1].second[it]) }.toSet()
        erail.selectedIndex = 3
        panelShot("emoji", emo)
        ecells.marks = emptySet()
        erail.selectedIndex = groups.size + 2
        ecells.columns = 3; ecells.textSp = 14f; ecells.rowHeightDp = 44f
        ecells.setItems(ctx.assets.open("data/kaomoji.txt").bufferedReader().readLines().filter { it.isNotBlank() })
        panelShot("emoji_kaomoji", emo)
        // 语音输入 (no bottom row), AI 改写, 翻译栏
        val vp = com.kk.shurufa.keyboard.VoicePanel(ctx, prefs).apply {
            look = com.kk.shurufa.keyboard.VoicePanel.Look.LISTENING; status = "正在听…停顿 3 秒自动结束"; language = "中文"; engineName = "SenseVoice"
            partial = "明天下午三点在学校门口"; mode = "转文字"; canUndo = true
        }
        vp.setLevel(0.6f); idle(200)
        panelShot("voice", vp) { it.activeTool = "mic" }
        vp.translating = true; vp.mode = "译成English"; vp.partial = "明天下午三点在学校门口"
        panelShot("voice_translate", vp) { it.activeTool = "mic" }
        val ap = com.kk.shurufa.keyboard.AiPanel(ctx, prefs).apply {
            actions = com.kk.shurufa.online.AiWriter.ACTIONS.map { com.kk.shurufa.keyboard.AiPanel.Act(it.id, it.title, it.group) }
            sourceLabel = "全文"; source = "今天天气真好我门去公园吧，顺便买点水果"; provider = "Claude · opus-5-5"; doneTitle = "纠错"
            setResult("今天天气真好，我们去公园吧，顺便买点水果。", "今天天气真好我门去公园吧，顺便买点水果")
        }
        panelShot("ai", ap) { it.activeTool = "ai" }
        val tstrip = com.kk.shurufa.keyboard.TranslateStrip(ctx, prefs).apply {
            languages = com.kk.shurufa.online.TranslateService.LANGS
            fromName = "自动·中文"; toName = "English"; engineName = "DeepSeek"
            source = "明天下午三点在学校门口见"; result = "See you at the school gate at three tomorrow afternoon."
        }
        val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(tstrip, LinearLayout.LayoutParams(-1, -2))
        tcol.addView(CandidateBar(ctx, prefs).apply { activeTool = "translate" }, LinearLayout.LayoutParams(-1, -2))
        tcol.addView(KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener; setRows(LayoutRepo(ctx).keyboard("zh").rows) }, LinearLayout.LayoutParams(-1, -2))
        panelShotWrap("translate", tcol, noBar = true)
        // 长按选肤色: rows of the emoji's forms
        val d = com.kk.shurufa.ime.EmojiData(ctx).apply { load() }
        val opts = (listOf("👮") + d.variants["👮"]!!).chunked(6).map { r -> r.map { Output.of(it) } }
        val frame = FrameLayout(ctx)
        val ov = PopupOverlay(ctx, prefs)
        val g2 = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply { columns = 7; rowHeightDp = 46f; textSp = 25f; cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.PLAIN }
        frame.addView(g2, FrameLayout.LayoutParams(-1, -1)); frame.addView(ov, FrameLayout.LayoutParams(-1, -1))
        val fw = ctx.resources.displayMetrics.widthPixels; val fh = (ctx.resources.displayMetrics.density * 300).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, fw, fh)
        g2.setItems(d.groups[1].second.take(28))
        val dd = ctx.resources.displayMetrics.density
        ov.showOptionRows(android.graphics.RectF(fw * 0.3f, fh - 60 * dd, fw * 0.3f + 48 * dd, fh - 14 * dd), 46 * dd, opts, fw * 0.3f)
        idle(400)
        val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888); frame.draw(Canvas(bmp))
        File("build/screens/emoji_tones.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 查找: the bar lists matches for the word before the cursor
        val found = com.kk.shurufa.ime.EmojiData(ctx).apply { load() }.search("开心").take(20)
        val kb2 = KeyboardView(ctx, prefs).apply { listener = this@KeyboardRenderTest.listener; setRows(LayoutRepo(ctx).keyboard("zh").rows) }
        panelShot("emoji_search", kb2, 200) { it.showPredictions(found, "查找「开心」· 点按发送"); it.activeTool = "emoji" }
    }
}
