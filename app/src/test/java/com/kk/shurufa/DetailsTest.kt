package com.kk.shurufa

import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.KkImeService
import com.kk.shurufa.ime.Muted
import com.kk.shurufa.ime.PinyinTones
import com.kk.shurufa.ime.WordPredictor
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.YearMonth

/** 逐字声调, 主题微调, 不再出现, 慢慢忘记, and backspace taking back automatic changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun toneMarksSitWherePinyinPutsThem() {
        assertEquals("nǐ", PinyinTones.mark("ni", 3))
        assertEquals("hǎo", PinyinTones.mark("hao", 3))
        assertEquals("guī", PinyinTones.mark("gui", 1))
        assertEquals("liù", PinyinTones.mark("liu", 4))
        assertEquals("dōu", PinyinTones.mark("dou", 1))
        assertEquals("xué", PinyinTones.mark("xue", 2))
        assertEquals("nǚ", PinyinTones.mark("nv", 3))
        assertEquals("lüè", PinyinTones.mark("lve", 4))
        assertEquals("ni", PinyinTones.mark("ni", 0))
        assertEquals("zh", PinyinTones.mark("zh", 2))
    }

    @Test fun themeTuning() {
        val prefs = Prefs(ctx)
        prefs.put(Prefs.STYLE, "paper"); prefs.put(Prefs.THEME, "light")
        val base = KbTheme.resolve(ctx, prefs)
        prefs.put(Prefs.THEME_WARMTH, -40)
        val cool = KbTheme.resolve(ctx, prefs)
        fun chroma(c: Int) = ((c shr 16) and 0xFF) - (c and 0xFF)   // red minus blue: how yellow the paper is
        assertTrue("less yellow", chroma(cool.background) < chroma(base.background))
        assertEquals("the accent is left alone", base.accent, cool.accent)
        prefs.put(Prefs.THEME_WARMTH, 0); prefs.put(Prefs.THEME_SHADE, 40)
        val deep = KbTheme.resolve(ctx, prefs)
        assertTrue(android.graphics.Color.luminance(deep.background) < android.graphics.Color.luminance(base.background))
        assertEquals(base.surface, deep.surface)
        prefs.put(Prefs.ACCENT, "custom"); prefs.put(Prefs.ACCENT_HUE, 120)
        assertNotEquals(base.accent, KbTheme.resolve(ctx, prefs).accent)
        prefs.sp.edit().clear().apply()
    }

    @Test fun mutedWords() {
        Muted.file(ctx).delete()
        Muted.add(ctx, "拟好"); Muted.add(ctx, "你号")
        assertEquals(setOf("你号", "拟好"), Muted.file(ctx).readLines().filter { it.isNotEmpty() }.toSet())
        Muted.remove(ctx, "你号")
        assertFalse(Muted.has(ctx, "你号")); assertTrue(Muted.has(ctx, "拟好"))
        Muted.merge(ctx, "泥好\n")
        assertEquals(setOf("拟好", "泥好"), Muted.all(ctx))
        Muted.remove(ctx, "拟好"); Muted.remove(ctx, "泥好")
    }

    @Test fun learnedWordsFadeEveryThreeMonths() {
        val f = File(ctx.filesDir, "learned_en.tsv")
        f.writeText("t\t${YearMonth.now().minusMonths(7)}\nw\tkaicheng\t1\nw\tphysfak\t9\nf\tkaicheng\tKaicheng\nb\t^\tphysfak\t4\n")
        val p = WordPredictor(ctx, "en"); p.load()
        val until = System.currentTimeMillis() + 10_000
        while (!p.ready && System.currentTimeMillis() < until) Thread.sleep(20)
        // two quarters went by: 9 → 2, 1 → gone (and its written form with it)
        assertTrue(p.complete("phys", emptyList()).contains("physfak"))
        assertTrue(p.complete("kaich", emptyList()).none { it.equals("kaicheng", true) })
        p.save(); Thread.sleep(300)
        val text = f.readText()
        assertTrue(text, text.contains("w\tphysfak\t2"))
        assertTrue(text, text.startsWith("t\t${YearMonth.now().minusMonths(1)}"))
        f.delete()
    }

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    @Test fun backspaceTakesBackAutomaticChanges() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "en,zh") }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        fun idle() { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        fun type(s: String) { for (ch in s) { if (ch == ' ') ime.onFunction(Key(fn = "space")) else { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }; idle() } }
        fun back() { ime.onFunction(Key(fn = "backspace")); idle() }
        field.text.append("ok"); Selection.setSelection(field.text, 2); ime.onUpdateSelection(0, 0, 2, 2, -1, -1)
        // 双击空格 made a full stop: backspace gives the two spaces back
        type("  ")
        assertEquals("ok. ", field.text.toString())
        back(); assertEquals("ok  ", field.text.toString())
        back(); assertEquals("ok ", field.text.toString())
        // 3, then Chinese: the comma was a sentence's, full-width again
        field.text.clear(); field.text.append("共3,"); Selection.setSelection(field.text, 3); ime.onUpdateSelection(0, 0, 3, 3, -1, -1)
        ime.punct.typed(",", "，")
        ime.punct.restoreFullWidth("好的")
        assertEquals("共3，", field.text.toString())
        field.text.clear(); field.text.append("ok "); Selection.setSelection(field.text, 3); ime.onUpdateSelection(0, 0, 3, 3, -1, -1)
        // 双击句号 made an ellipsis: backspace gives the two periods back
        type("..")
        assertEquals("ok …", field.text.toString())
        back(); assertEquals("ok ..", field.text.toString())
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
    }
}
