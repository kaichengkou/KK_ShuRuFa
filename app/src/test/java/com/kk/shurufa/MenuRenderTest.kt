package com.kk.shurufa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.Output
import com.kk.shurufa.keyboard.PickList
import com.kk.shurufa.keyboard.PopupOverlay
import com.kk.shurufa.online.Llm
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Long-press menus, the 按原拼音 tag and the model lists, rendered to build/screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuRenderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs = Prefs(ctx).also { it.put(Prefs.ANIMATIONS, false) }
    private val d get() = ctx.resources.displayMetrics.density
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun save(name: String, v: View) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun frame(h: Int, vararg views: View): FrameLayout {
        val f = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        views.forEach { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        f.addView(col)
        val w = ctx.resources.displayMetrics.widthPixels
        f.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((h * d).toInt(), View.MeasureSpec.EXACTLY))
        f.layout(0, 0, w, (h * d).toInt())
        return f
    }

    @Test fun shortNames() {
        assertEquals("Gemini 3.5 Flash Lite", Llm.shortName("gemini-3.5-flash-lite"))
        assertEquals("Gemini Flash Lite（最新）", Llm.shortName("gemini-flash-lite-latest"))
        assertEquals("Claude Opus 5.5", Llm.shortName("claude-opus-5-5"))
        assertEquals("Claude Haiku 4.5", Llm.shortName("claude-haiku-4-5-20251001"))
        assertEquals("GPT-5 Mini", Llm.shortName("gpt-5-mini"))
        assertEquals("GPT-4o Mini Transcribe", Llm.shortName("gpt-4o-mini-transcribe"))
        assertEquals("DeepSeek Chat", Llm.shortName("deepseek-chat"))
        assertEquals("Gemma 4 31B", Llm.shortName("gemma-4-31b-it"))
        assertEquals("Qwen3 8B", Llm.shortName("Qwen/Qwen3-8B"))
        assertEquals("Gemini 3 Flash（预览）", Llm.shortName("gemini-3-flash-preview"))
    }

    @Test fun menus() {
        val kb = KeyboardView(ctx, prefs).apply { setRows(LayoutRepo(ctx).keyboard("zh").rows) }
        val bar = CandidateBar(ctx, prefs)
        val f = frame(330, bar, kb)
        val ov = PopupOverlay(ctx, prefs)
        f.addView(ov, FrameLayout.LayoutParams(-1, -1))
        f.measure(View.MeasureSpec.makeMeasureSpec(f.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(f.height, View.MeasureSpec.EXACTLY))
        f.layout(0, 0, f.width, f.height)
        val w = f.width.toFloat()
        val keyW = w / 10; val keyH = 50 * d
        val bottom = f.height - 8 * d
        fun key(cx: Float) = RectF(cx - keyW / 2, bottom - keyH, cx + keyW / 2, bottom)
        fun show(name: String, cx: Float, rows: List<List<Output>>, anchor: RectF = key(cx), h: Float = keyH) {
            ov.showOptionRows(anchor, h, rows, anchor.centerX()); idle(300); save(name, f); ov.hideOptions(); idle(300)
        }
        // keys: characters, one glyph size
        show("menu_key_period", w * 0.8f, listOf(listOf("……", "？", "！", "～", "——").map { Output.of(it) }))
        show("menu_key_l", w * 0.85f, listOf(listOf("？", "?", "¿").map { Output.of(it) }, listOf("L", "l", "ł").map { Output.of(it) }))
        // function keys and touch menus: words
        show("menu_language", w * 0.2f, listOf(listOf(Output("中文", "§lang:zh"), Output("English", "§lang:en"), Output("Русский", "§lang:ru"), Output("切换输入法", "§picker"), Output("设置", "§settings"))))
        show("menu_symbols", w * 0.08f, listOf(listOf(Output("数字", "§num"), Output("符号", "§sym"), Output("学术键盘", "§科学"), Output("文字编辑", "§cursor"), Output("剪贴板", "§clip"))))
        show("menu_enter", w * 0.9f, listOf(listOf(Output("换行", "§newline"), Output("发送", "§action"))))
        val cand = RectF(w * 0.25f, bar.height.toFloat(), w * 0.25f + 60 * d, bar.height * 2f)
        show("menu_candidate", 0f, listOf(listOf(Output("取消", "§cancel"), Output("不再优先「你好」", "§forget"))), cand, bar.height.toFloat())
        val clip = RectF(w * 0.6f, 120 * d, w * 0.95f, 164 * d)
        show("menu_clip", 0f, listOf(listOf(Output("取消", "§cancel"), Output("收藏", "§pin"), Output("删除", "§del"), Output("清空最近", "§clear"))), clip, 44 * d)
        // 按原拼音: level with the words
        bar.showPredictions(listOf("你好", "拟好", "你号", "泥好"), "按原拼音 ni hao"); f.invalidate(); save("bar_reselect", f)
        bar.showPredictions(listOf("你好", "拟好", "你号", "泥好")); f.invalidate(); save("bar_predict", f)
        bar.showCandidates("ni hao", listOf("你好", "拟好", "你号", "泥好"), emptyList(), -1); f.invalidate(); save("bar_compose", f)
        bar.showPredictions(listOf("找时间一起去吧", "看看天安门和故宫", "买张高铁票就出发", "玩", "旅游", "看看"), "", listOf("云", "云", "云")); f.invalidate(); save("bar_cloud", f)
        // 声调筛选, now a row in the opened candidates
        run {
            // 你好: 你 already set to the third tone, the tones of hao being picked
            val mark = com.kk.shurufa.ime.PinyinTones.Companion::mark
            val syl = com.kk.shurufa.keyboard.TabStrip(ctx, prefs).apply { compact = true; setTabs(listOf("0" to mark("ni", 3), "1" to mark("hao", 0)), "1") }
            val tabs = com.kk.shurufa.keyboard.TabStrip(ctx, prefs).apply { compact = true; setTabs(listOf("0" to "全部") + (1..4).map { "$it" to mark("hao", it) }, "3") }
            val grid = com.kk.shurufa.keyboard.GridPanel(ctx, prefs).apply {
                cellStyle = com.kk.shurufa.keyboard.GridPanel.CellStyle.FLAT; columns = 8; rowHeightDp = 50f; textSp = 19f; highlightFirst = true
            }
            val tf = frame(200, CandidateBar(ctx, prefs).apply { showCandidates("ni hao", listOf("你好", "拟好", "你", "拟", "尼", "泥"), emptyList(), -1) }, syl, tabs,
                grid.also { it.layoutParams = LinearLayout.LayoutParams(-1, (100 * d).toInt()) })
            grid.setItems(listOf("你好", "拟好", "你", "拟", "旎", "伱", "妳", "祢", "儗", "苨"))
            tf.invalidate(); idle(300); save("cand_tone", tf)
        }
        bar.showCandidates("wo men zai xue xiao men kou jian mian", listOf("我们在学校门口", "我们在学校门口见面", "我们在", "我们", "我"), listOf("", "AI"), -1)
        f.invalidate(); save("bar_ai", f)
        bar.showMath("uni", simple = true); bar.showMoney("1050" to "壹仟零伍拾元整"); f.invalidate(); save("bar_money", f)
        bar.showMoney(null)
    }

    private fun setPicking(v: Any, value: Any?) {
        v.javaClass.getDeclaredField("picking").apply { isAccessible = true }.set(v, value)
        (v.javaClass.getDeclaredField("pick").apply { isAccessible = true }.get(v) as PickList).reveal()
    }

    @Test fun pickers() {
        val models = listOf("gemini|gemini-3.5-flash-lite" to "Gemini", "gemini|gemini-3.5-flash" to "Gemini", "gemini|gemini-flash-lite-latest" to "Gemini",
            "gemini|gemini-3.8-flash" to "Gemini", "claude|claude-opus-5-5" to "Claude", "claude|claude-haiku-4-5-20251001" to "Claude",
            "openai|gpt-5-mini" to "OpenAI", "openai|gpt-4o-mini-transcribe" to "OpenAI", "deepseek|deepseek-chat" to "DeepSeek")
            .map { (t, p) -> PickList.Item(t, Llm.shortName(t.substringAfter('|')), p) }
        val vp = com.kk.shurufa.keyboard.VoicePanel(ctx, prefs).apply {
            look = com.kk.shurufa.keyboard.VoicePanel.Look.READY; status = "点一下开始说话"; language = "中文"; engineName = "Gemini"
            engines = listOf(PickList.Item("whisper", "本地模型", "SenseVoice · Whisper"), PickList.Item("system", "系统识别", "Android")) + models
            engine = "gemini|gemini-3.5-flash-lite"
        }
        val f1 = frame(250, CandidateBar(ctx, prefs).apply { activeTool = "mic" }, vp.also { it.layoutParams = LinearLayout.LayoutParams(-1, (240 * d).toInt()) })
        setPicking(vp, true); f1.invalidate(); idle(100)
        f1.layout(0, 0, f1.width, f1.height); save("pick_voice", f1)
        val ap = com.kk.shurufa.keyboard.AiPanel(ctx, prefs).apply {
            actions = com.kk.shurufa.online.AiWriter.ACTIONS.map { com.kk.shurufa.keyboard.AiPanel.Act(it.id, it.title, it.group) }
            sourceLabel = "全文"; source = "今天天气真好我门去公园吧"; provider = "Gemini 3.5 Flash Lite"
            targets = models.filter { !it.name.contains("Transcribe") }; currentTarget = "gemini|gemini-3.5-flash-lite"
        }
        val f2 = frame(250, CandidateBar(ctx, prefs).apply { activeTool = "ai" }, ap.also { it.layoutParams = LinearLayout.LayoutParams(-1, (240 * d).toInt()) })
        // the actions of each kind, wrapped onto lines; the switch picks the kind
        f2.invalidate(); idle(100); save("ai_rewrite", f2)
        ap.javaClass.getDeclaredField("group").apply { isAccessible = true }.set(ap, "翻译")
        f2.invalidate(); idle(400); save("ai_translate", f2)
        setPicking(ap, true); f2.invalidate(); save("pick_ai", f2)
        setPicking(ap, false)
        ap.doneTitle = "润色"
        ap.setResult("我昨天去那家店买东西，发现他们家的东西其实挺贵的，不过质量还行，就是服务态度不太好。".repeat(4), null)
        f2.invalidate(); save("ai_long_top", f2)
        val t0 = android.os.SystemClock.uptimeMillis(); val cx = f2.width / 2f; val y0 = 200 * d
        val loc = IntArray(2); ap.getLocationInWindow(loc)
        fun ev(a: Int, y: Float, t: Long) = android.view.MotionEvent.obtain(t0, t0 + t, a, cx, y - loc[1], 0).also { ap.dispatchTouchEvent(it) }
        fun lev(a: Int, y: Float, t: Long) = android.view.MotionEvent.obtain(t0, t0 + t, a, cx, y * d, 0).also { ap.onTouchEvent(it) }
        lev(0, 170f, 0); lev(2, 160f, 50); lev(2, 130f, 100); lev(2, 100f, 150); lev(1, 100f, 400)
        assertEquals(true, (ap.javaClass.getDeclaredField("resScroll").apply { isAccessible = true }.get(ap) as Float) > 0f)
        f2.invalidate(); save("ai_long_scrolled", f2)
        val ts = com.kk.shurufa.keyboard.TranslateStrip(ctx, prefs).apply {
            languages = com.kk.shurufa.online.TranslateService.LANGS; fromName = "自动"; toName = "English"; engineName = "Gemini"
            engines = listOf(PickList.Item("mlkit", "离线翻译", "ML Kit")) + models; engine = "gemini|gemini-3.5-flash"
        }
        val f3 = frame(140, ts)
        setPicking(ts, "engine"); f3.invalidate(); save("pick_translate", f3)
    }
}
