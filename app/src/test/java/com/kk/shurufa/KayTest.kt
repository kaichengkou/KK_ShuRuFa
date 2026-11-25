package com.kk.shurufa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.Kay
import com.kk.shurufa.ime.KayLines
import com.kk.shurufa.ime.KkImeService
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.time.LocalDate
import java.time.LocalTime

/** 小K: its lines, its rules, its memory, its face, and how it shows on the keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KayTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val d get() = ctx.resources.displayMetrics.density

    @Test fun everyLineFitsTheBar() {
        // a whole year, every hour: one row next to 接着聊 and 已阅
        var day = LocalDate.of(2027, 1, 1)
        val seen = HashSet<String>()
        while (day.year == 2027) {
            for (h in listOf(6, 10, 12, 15, 20, 1)) {
                val l = KayLines.local(day, LocalTime.of(h, 0), 40, if (day.dayOfMonth == 3) 4200 else 800)
                assertTrue("$day $h: $l", l.length <= 26)
                assertFalse(l, l.contains('！') || l.contains('?') || l.contains('？'))
                seen += l
            }
            day = day.plusDays(1)
        }
        assertTrue("varied: ${seen.size}", seen.size >= 40)
        assertEquals("过年好，新的一年也一起 (｡･ω･｡)ﾉ", KayLines.local(LocalDate.of(2027, 2, 6), LocalTime.of(9, 0), 40, 0))
        val dongzhi = (18..24).map { LocalDate.of(2026, 12, it) }.first { com.kk.shurufa.ime.SolarTerms.on(it) == "冬至" }
        assertTrue(KayLines.local(dongzhi, LocalTime.of(9, 0), 40, 0).startsWith("冬至"))
        assertEquals("初次见面，以后多指教 (・ω・)ﾉ", KayLines.local(LocalDate.of(2027, 3, 3), LocalTime.of(9, 0), 1, 0))
        assertTrue(KayLines.facts(dongzhi, 30, 1200).any { it.contains("冬至") })
        assertEquals("冬至。吃饺子 (｡･ω･｡)", KayLines.cleanLine("“冬至。吃饺子 (｡･ω･｡)”\n"))
        assertNull(KayLines.cleanLine("这是一句很长很长的话，长到放不进键盘上的那一行里，所以不要它了"))
    }

    @Test fun oneLineADayAndTalking() {
        val prefs = Prefs(ctx)
        prefs.state.edit().clear().commit()
        val kay = Kay(ctx, prefs)
        val today = LocalDate.now()
        assertTrue(kay.greetingDue(LocalTime.of(9, 0), today))
        assertFalse("not before five", kay.greetingDue(LocalTime.of(4, 0), today))
        kay.markGreeted(today)
        assertFalse(kay.greetingDue(LocalTime.of(9, 0), today))
        assertTrue(kay.greetingDue(LocalTime.of(9, 0), today.plusDays(1)))
        assertEquals(1, kay.age(today))
        // what was said stays in the conversation only
        kay.forgetTalk()
        assertEquals("那周四早点睡 (・ω・)", kay.heard("周五要考俄语", "那周四早点睡 (・ω・)\n"))
        assertTrue(kay.chatUser("在吗").contains("主人：周五要考俄语"))
        assertFalse(kay.chatSystem().contains("记住"))
        assertFalse(File(ctx.filesDir, "kay_memory.txt").exists())
        // the cap on talking
        prefs.put(Prefs.KAY_CHAT_CAP, 2)
        assertTrue(kay.mayTalk()); kay.talked(); kay.talked()
        assertFalse(kay.mayTalk())
        prefs.sp.edit().remove(Prefs.KAY_CHAT_CAP).apply()
        prefs.state.edit().clear().commit()
    }

    private fun save(b: Bitmap, name: String) {
        File("build/screens").mkdirs(); File("build/screens/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 小鲨鱼: every face, what it wears, and its moves frame by frame (for build/screens/shark_frames). */
    @Test fun shark() {
        val S = com.kk.shurufa.keyboard.Shark
        val cellPx = 8f
        val faces = com.kk.shurufa.keyboard.Shark.Face.values()
        val col = 20 * cellPx
        val b = Bitmap.createBitmap((col * 6).toInt(), (col * 4).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(0xFFF7F5F0.toInt())
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = 0xFF666666.toInt(); textAlign = android.graphics.Paint.Align.CENTER }
        faces.forEachIndexed { i, f ->
            val x = (i % 6) * col + col / 2; val y = (i / 6) * col + col / 2
            S.draw(c, x, y, 13 * cellPx, com.kk.shurufa.keyboard.Shark.Pose(f, blush = f == com.kk.shurufa.keyboard.Shark.Face.PAT || f == com.kk.shurufa.keyboard.Shark.Face.HAPPY), wear = null)
            c.drawText(f.kaomoji, x, y + col / 2 - 8, tp)
        }
        var d0 = LocalDate.of(2027, 1, 1)
        val wears = LinkedHashMap<String, com.kk.shurufa.keyboard.Shark.Wear>()
        while (d0.year == 2027) { S.wearFor(d0)?.let { wears.putIfAbsent(it.name, it) }; d0 = d0.plusDays(1) }
        wears.values.forEachIndexed { i, w ->
            val x = i * col + col / 2; val y = 2 * col + col / 2
            S.draw(c, x, y, 13 * cellPx, com.kk.shurufa.keyboard.Shark.Pose(), wear = w)
            c.drawText(w.name, x, y + col / 2 - 8, tp)
        }
        val moves = listOf("跳起" to com.kk.shurufa.keyboard.Shark.Pose(dy = -2), "挥鳍" to com.kk.shurufa.keyboard.Shark.Pose(com.kk.shurufa.keyboard.Shark.Face.HAPPY, wave = true),
            "蓄力" to com.kk.shurufa.keyboard.Shark.Pose(squash = true), "甩背鳍" to com.kk.shurufa.keyboard.Shark.Pose(flick = true),
            "吐泡泡" to com.kk.shurufa.keyboard.Shark.Pose(com.kk.shurufa.keyboard.Shark.Face.LOOKUP, extra = com.kk.shurufa.keyboard.Shark.Extra.BUBBLES, phase = 3),
            "睡着" to com.kk.shurufa.keyboard.Shark.Pose(com.kk.shurufa.keyboard.Shark.Face.SLEEP, extra = com.kk.shurufa.keyboard.Shark.Extra.Z, phase = 1))
        moves.forEachIndexed { i, (name, p) ->
            val x = i * col + col / 2; val y = 3 * col + col / 2
            S.draw(c, x, y, 13 * cellPx, p, wear = null)
            c.drawText(name, x, y + col / 2 - 8, tp)
        }
        save(b, "shark_sheet")

        // its moves, frame by frame every 40 ms, for an animation of them
        val dir = File("build/screens/shark_frames").apply { deleteRecursively(); mkdirs() }
        val L = com.kk.shurufa.keyboard.SharkLife
        var t = 10_000_000L
        var n = 0
        fun film(label: String, ms: Long, act: (Long) -> Unit) {
            act(t)
            var k = 0L
            while (k < ms) {
                // a space key, large: 小K, then the language (or what it says), nudged by a nibble
                val fb = Bitmap.createBitmap(520, 250, Bitmap.Config.ARGB_8888)
                val fc = Canvas(fb); fc.drawColor(0xFFECEEF2.toInt())
                val key = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                fc.drawRoundRect(20f, 40f, 500f, 190f, 28f, 28f, key)
                val (pose, _) = L.frame(t + k, 14, still = true)
                S.draw(fc, 130f, 118f, 13 * 7f, pose, wear = null)
                val words = L.saying(t + k)
                val lp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 46f; color = 0xFF737A86.toInt() }
                fc.drawText("中文", 200f + L.push(t + k) * 7f, 134f, lp)
                if (words != null) {
                    val sp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = 0xFF9AA0AA.toInt(); alpha = (255 * L.sayingShown(t + k)).toInt(); textAlign = android.graphics.Paint.Align.CENTER }
                    fc.drawText(words, 260f, 178f, sp)
                }
                tp.textSize = 24f; fc.drawText(label, 260f, 232f, tp)
                File(dir, "%04d.png".format(n++)).outputStream().use { fb.compress(Bitmap.CompressFormat.PNG, 100, it) }
                k += 40
            }
            t += ms + 60_000
        }
        film("打招呼", 1400) { L.greet(it) }
        film("平常：眨眼、甩鳍", 1800) { L.key(it) }
        film("打字飞快", 900) { now -> for (i in 0 until 20) L.key(now - i * 100) }
        film("删太多：捂眼", 1600) { now -> for (i in 0 until 7) L.delete(now - (6 - i) * 120) }
        film("被摸头", 1600) { L.pat(it) }
        film("消息发出去了", 1100) { L.sent(it) }
        film("思考中", 1600) { L.thinking = true }
        L.thinking = false
        film("很久没来：惊醒", 1100) { now -> L.woke(now) }
        film("打盹", 2400) { now -> L.key(now - 120_000) }
        for ((i, name) in listOf("唱歌", "敲电脑", "晒太阳", "啃小鱼干", "做操").withIndex())
            film("长动作：$name", 7000) { now -> L.key(now - 4000); L.longAct(now, i) }
        film("边打字边做事：不被打断", 2400) { now -> L.key(now - 4000); L.longAct(now, 1); for (i in 0 until 8) L.key(now + i); L.delete(now + 9); L.sent(now + 10) }
        film("平时：它自己找点事做", 40_000) { now -> L.key(now) }
    }

    @Test fun chatPanel() {
        val prefs = Prefs(ctx)
        val p = com.kk.shurufa.keyboard.AiPanel(ctx, prefs).apply {
            theme = KbTheme.resolve(ctx, prefs)
            actions = com.kk.shurufa.online.AiWriter.ACTIONS.map { com.kk.shurufa.keyboard.AiPanel.Act(it.id, it.title, it.group) }
            chatTab = "聊天"; kayName = "小K"; provider = "Gemini 3.5 Flash Lite"; place = "🇯🇵 日本"; placeOk = true
            selectGroup("聊天")
            val sb = SpannableStringBuilder()
            fun line(who: String, text: String) { if (sb.isNotEmpty()) sb.append("\n"); sb.append(who).append("  ").append(text) }
            line("小K", "小雪。宜热汤 (｡･ω･｡)"); line("我", "今天好冷，还要去实验室"); line("小K", "那就把手揣兜里走过去，回来给你热汤的位置留着 ( ˘ω˘ )")
            chat = sb; chatHasAnswer = true
        }
        val w = ctx.resources.displayMetrics.widthPixels
        p.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((236 * d).toInt(), View.MeasureSpec.EXACTLY))
        p.layout(0, 0, w, (236 * d).toInt())
        val b = Bitmap.createBitmap(w, (236 * d).toInt(), Bitmap.Config.ARGB_8888); p.draw(Canvas(b))
        save(b, "kay_chat")
    }

    /** The 小K panel in each of its states (for build/screens/kay_panel_*.png). */
    @Test fun panelStates() {
        val prefs = Prefs(ctx)
        val w = ctx.resources.displayMetrics.widthPixels
        val h = (250 * d).toInt()
        fun shot(name: String, setup: com.kk.shurufa.keyboard.AiPanel.() -> Unit) {
            val p = com.kk.shurufa.keyboard.AiPanel(ctx, prefs).apply {
                theme = KbTheme.resolve(ctx, prefs)
                actions = com.kk.shurufa.online.AiWriter.ACTIONS.map { com.kk.shurufa.keyboard.AiPanel.Act(it.id, it.title, it.group) }
                chatTab = "聊天"; kayName = "小K"; provider = "Gemini 3.5 Flash Lite"; place = "🇯🇵 日本"; placeOk = true
                sourceLabel = "全文"; source = "今天天气真好我门去公园吧，顺便买点水果"
                setup()
            }
            p.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            p.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); p.draw(Canvas(b))
            save(b, "kay_panel_$name")
        }
        shot("chat_empty") { selectGroup("聊天") }
        shot("chat") {
            selectGroup("聊天")
            chat = "小K  小雪。宜热汤 (｡･ω･｡)\n我  今天好冷，还要去实验室\n小K  那就把手揣兜里走过去，回来给你热汤的位置留着 ( ˘ω˘ )"; chatHasAnswer = true
        }
        shot("chat_thinking") { selectGroup("聊天"); chat = "我  晚饭吃什么"; running = "chat" }
        shot("rewrite") { selectGroup("改写") }
        shot("rewrite_result") { selectGroup("改写"); doneTitle = "纠错"; setResult("今天天气真好，我们去公园吧，顺便买点水果。", "今天天气真好我门去公园吧，顺便买点水果") }
        shot("translate") { selectGroup("翻译") }
        shot("setup") { needsSetup = true }
    }

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    /**
     * 小K's way in: the 小K key opens its page (小K large, the line of the day, 改写 · 翻译 · 聊天);
     * a choice brings the keyboard back with the strip above it, in that way; the strip's list
     * switches ways. Frames to build/screens/kay_flow_*.png.
     */
    @Test fun kayFlow() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "zh,en") }
        prefs.state.edit().clear().putString(Prefs.KAY_GREETED, java.time.LocalDate.now().toString()).commit()
        prefs.sp.edit().putString("llm_gemini_key", "test").putString(Prefs.AI_PROVIDER, "gemini").commit()
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        field.text.append("今天天气真好我门去公园吧，顺便买点水果")
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400)) }
        idle()
        val w = ctx.resources.displayMetrics.widthPixels
        fun shot(name: String) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val col = (view as ViewGroup).getChildAt(0)
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(b))
            save(Bitmap.createBitmap(b, 0, col.top.coerceAtLeast(0), b.width, (col.bottom - col.top.coerceAtLeast(0)).coerceAtLeast(1)), name)
        }
        fun panel() = KkImeService::class.java.getDeclaredField("panel").apply { isAccessible = true }.get(ime).toString()
        fun feature() = KkImeService::class.java.getDeclaredField("translate").apply { isAccessible = true }.get(ime) as com.kk.shurufa.ime.TranslateFeature
        // the 小K key: its page
        ime.onTool("ai"); idle()
        assertEquals("AI", panel())
        shot("kay_flow_1_home")
        // 改写: the keyboard comes back, the strip above it holds the field's text
        ime.openStrip("rewrite"); idle()
        assertEquals("LETTERS", panel())
        assertEquals("rewrite", feature().mode)
        assertEquals("今天天气真好我门去公园吧，顺便买点水果", ime.query.toString())
        shot("kay_flow_2_rewrite")
        // its list, open
        val strip = feature().strip
        strip.javaClass.getDeclaredField("picking").apply { isAccessible = true }.set(strip, "menu")
        strip.requestLayout(); idle()
        shot("kay_flow_3_menu")
        strip.closeLists(); idle()
        // a way picked from the list: 翻译, the text stays
        strip.onPick?.invoke("menu", "mode:translate"); idle()
        assertEquals("translate", feature().mode)
        shot("kay_flow_4_translate")
        // 聊天: what is typed is said to 小K
        strip.onPick?.invoke("menu", "mode:chat"); idle()
        assertEquals("chat", feature().mode)
        assertEquals("", ime.query.toString())
        shot("kay_flow_5_chat")
        // ✕: the strip goes, the letters stay
        strip.onClose?.invoke(); idle()
        assertEquals(null, ime.sink)
        ime.onDestroy()
        prefs.sp.edit().remove(Prefs.LANGUAGES).remove("llm_gemini_key").remove(Prefs.AI_PROVIDER).apply()
        prefs.state.edit().clear().commit()
    }

    @Test fun greetingOnTheBar() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "en,zh") }
        prefs.state.edit().clear().commit()
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }
        idle()
        fun greeting() = KkImeService::class.java.getDeclaredField("greeting").apply { isAccessible = true }.get(ime) as String?
        val w = ctx.resources.displayMetrics.widthPixels
        fun shot(name: String) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val col = (view as ViewGroup).getChildAt(0)
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(b))
            save(Bitmap.createBitmap(b, 0, col.top.coerceAtLeast(0), b.width, (col.bottom - col.top.coerceAtLeast(0)).coerceAtLeast(1)), name)
        }
        if (System.getProperty("kk.hour")?.toIntOrNull() ?: LocalTime.now().hour >= 5) {
            assertTrue("the line of the day shows", greeting() != null)
            shot("kay_greeting")
            // a key puts it away; it does not come back the same day
            val k = Key(main = Output.of("a")); ime.onOutput(k, k.main!!); idle()
            assertNull(greeting())
            ime.onFinishInputView(true)
            ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
            assertNull("once a day", greeting())
        }
        // the toolbar, with 小K where ✦ was
        field.text.clear(); idle(); ime.onFunction(Key(fn = "backspace")); idle()
        shot("kay_toolbar")
        ime.onDestroy()
        prefs.sp.edit().remove(Prefs.LANGUAGES).apply()
        prefs.state.edit().clear().commit()
    }
}
