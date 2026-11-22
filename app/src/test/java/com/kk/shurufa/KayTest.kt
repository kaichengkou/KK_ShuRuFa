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
import com.kk.shurufa.keyboard.KayFace
import com.kk.shurufa.keyboard.KayLife
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Colors
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
        assertEquals("初次见面，我住在这里了 (・ω・)ﾉ", KayLines.local(LocalDate.of(2027, 3, 3), LocalTime.of(9, 0), 1, 0))
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

    @Test fun faces() {
        // every mood, and a blink, at a size to look at
        val t = KbTheme.resolve(ctx, Prefs(ctx))
        val size = 64 * d
        val moods = KayLife.Mood.values()
        val b = Bitmap.createBitmap(((moods.size + 1) * 90 * d).toInt(), (100 * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(t.background)
        moods.forEachIndexed { i, m -> KayFace.draw(c, (45 + i * 90) * d, 50 * d, size, t.inkSoft, t.accentSoft, 0x8CF08C9B.toInt(), 1_000_000L, 12, m) }
        save(b, "kay_faces")
    }

    /** 设计稿: every style in four moods, at the toolbar's size, and on the space key, light and dark. */
    @Test fun designSheet() {
        val prefs = Prefs(ctx)
        prefs.put(Prefs.THEME, "light"); val light = KbTheme.resolve(ctx, prefs)
        prefs.put(Prefs.THEME, "dark"); val dark = KbTheme.resolve(ctx, prefs)
        prefs.sp.edit().remove(Prefs.THEME).apply()
        val rowH = 96 * d
        val styles = KayFace.STYLES.keys.toList()
        val b = Bitmap.createBitmap((760 * d).toInt(), (rowH * styles.size + 40 * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(android.graphics.Color.WHITE)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; color = 0xFF555555.toInt() }
        val heads = listOf("平静" to 70f, "专注" to 140f, "被摸头" to 210f, "打盹" to 280f, "工具栏" to 355f, "空格键 · 浅色" to 470f, "空格键 · 深色" to 640f)
        for ((h, x) in heads) { tp.textAlign = android.graphics.Paint.Align.CENTER; c.drawText(h, x * d, 24 * d, tp) }
        val moods = listOf(KayLife.Mood.CALM, KayLife.Mood.BUSY, KayLife.Mood.HAPPY, KayLife.Mood.DOZE)
        val blush = 0x8CF08C9B.toInt()
        styles.forEachIndexed { row, st ->
            val y = 40 * d + row * rowH + rowH / 2
            tp.textAlign = android.graphics.Paint.Align.LEFT; tp.color = 0xFF222222.toInt(); tp.textSize = 15 * d
            c.drawText("${'A' + row} ${KayFace.STYLES[st]}", 8 * d, y + 5 * d, tp)
            tp.color = 0xFF555555.toInt(); tp.textSize = 13 * d
            val t = light
            moods.forEachIndexed { i, m -> KayFace.draw(c, (70 + i * 70) * d + 30 * d, y, 46 * d, t.inkSoft, t.accentSoft, blush, 1_000_000L, 12, m, t.accent, st) }
            // the toolbar, as it is: a strip of background
            val bg = android.graphics.Paint().apply { color = t.background }
            c.drawRect(330 * d, y - 22 * d, 385 * d, y + 22 * d, bg)
            KayFace.draw(c, 357 * d, y, 25 * d, t.inkSoft, t.soft, blush, 1_000_000L, 12, KayLife.Mood.CALM, t.accent, st)
            // the space key, light and dark: the face just before the language
            for ((k, th) in listOf(light, dark).withIndex()) {
                val kx = (395 + k * 170) * d
                bg.color = th.background; c.drawRect(kx - 6 * d, y - 26 * d, kx + 156 * d, y + 26 * d, bg)
                bg.color = th.surface
                c.drawRoundRect(kx, y - 20 * d, kx + 150 * d, y + 20 * d, 8 * d, 8 * d, bg)
                val lp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 14.5f * d; color = th.hint; textAlign = android.graphics.Paint.Align.LEFT }
                val label = "中文"; val lw = lp.measureText(label); val face = 17 * d
                val start = kx + 75 * d - (face + 5 * d + lw) / 2
                KayFace.draw(c, start + face / 2, y, face, th.hint, if (st == "dots") 0 else Colors.alpha(th.ink, 0x16), blush, 1_000_000L, 12, KayLife.Mood.CALM, th.accent, st)
                c.drawText(label, start + face + 5 * d, y + 5 * d, lp)
            }
        }
        save(b, "kay_designs")
    }

    @Test fun chatPanel() {
        val prefs = Prefs(ctx)
        val p = com.kk.shurufa.keyboard.AiPanel(ctx, prefs).apply {
            theme = KbTheme.resolve(ctx, prefs)
            actions = com.kk.shurufa.online.AiWriter.ACTIONS.map { com.kk.shurufa.keyboard.AiPanel.Act(it.id, it.title, it.group) }
            chatTab = "聊天"; kayName = "小K"; provider = "Gemini 3.5 Flash Lite"
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

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
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
