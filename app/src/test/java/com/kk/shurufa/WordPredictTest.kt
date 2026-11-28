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
import com.kk.shurufa.ime.WordPredictor
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** 俄语联想: the tables, the word splitting, and typing Russian into a field through the service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordPredictTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun loaded(lang: String = "ru"): WordPredictor {
        val p = WordPredictor(ctx, lang)
        p.load()
        val until = System.currentTimeMillis() + 10_000
        while (!p.ready && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("tables load", p.ready)
        return p
    }

    /** 不再联想 on a Russian word: it is kept off the completions and the next words alike. */
    @Test fun mutedWordsStayOff() {
        val p = loaded()
        val first = p.complete("при", emptyList()).first()
        com.kk.shurufa.ime.Muted.add(ctx, first.lowercase())
        try {
            assertTrue("$first is muted", first !in p.complete("при", emptyList()))
            assertTrue(p.following(emptyList(), capital = false).none { it.equals(first, ignoreCase = true) })
        } finally { com.kk.shurufa.ime.Muted.remove(ctx, first.lowercase()) }
        assertEquals(first, p.complete("при", emptyList()).first())
    }

    /** Learned words are found by their first letters, and the index follows what is learned. */
    @Test fun learnedWordsByPrefix() {
        val p = loaded()
        p.learn(emptyList(), "Шмыгалово")
        assertTrue(p.complete("шмы", emptyList()).any { it.equals("Шмыгалово", ignoreCase = true) })
        p.forget("Шмыгалово")
        assertTrue(p.complete("шмы", emptyList()).none { it.equals("Шмыгалово", ignoreCase = true) })
    }

    @Test fun splitsTheTextBeforeTheCaret() {
        assertEquals("мо" to listOf("я", "не"), WordPredictor.split("Ну, я не мо"))
        assertEquals("" to listOf("я", "не"), WordPredictor.split("я не "))
        assertEquals("При" to emptyList<String>(), WordPredictor.split("При"))
        assertEquals("" to emptyList<String>(), WordPredictor.split("Привет. "))
        assertEquals("ка" to emptyList<String>(), WordPredictor.split("привет, ка"))
        assertEquals("" to listOf("кто-то"), WordPredictor.split("кто-то "))
        assertTrue(WordPredictor.sentenceStart("Привет. "))
    }

    @Test fun completesAndPredicts() {
        val p = loaded()
        val pri = p.complete("при", emptyList())
        assertTrue("$pri", "привет" in pri)
        assertEquals("Привет", p.complete("При", emptyList()).first { it.startsWith("Прив") })
        // a finished word is offered first, so it can be confirmed
        assertEquals("как", p.complete("как", emptyList()).first())
        // what was said before steers the completion: я не мо… → могу
        assertEquals("могу", p.complete("мо", listOf("я", "не")).first())
        val afterYa = p.following(listOf("я"), capital = false)
        assertTrue("$afterYa", afterYa.take(3).contains("не"))
        assertTrue(p.following(listOf("спасибо"), capital = false).take(3).contains("за"))
        assertTrue(p.following(emptyList(), capital = true).first().first().isUpperCase())
    }

    @Test fun learnsWhatIsWritten() {
        val p = loaded()
        p.clearLearned()
        repeat(3) { p.learn(listOf("очень"), "кайфово") }
        assertEquals("кайфово", p.complete("кай", emptyList()).first())
        assertEquals("кайфово", p.following(listOf("очень"), capital = false).first())
        p.clearLearned()
        assertTrue("кайфово" !in p.complete("кай", emptyList()))
    }

    /** Names, patronymics, abbreviations: remembered as written, offered after what came before. */
    @Test fun remembersNamesAsWritten() {
        val p = loaded(); p.clearLearned()
        // "Андрей Леонидович", at the start of a message, and "физфак МГУ" inside one
        p.learn(emptyList(), "Андрей", sentenceStart = true)
        p.learn(listOf("Андрей"), "Леонидович")
        p.learn(listOf("на"), "физфак")
        p.learn(listOf("физфак"), "МГУ")
        assertEquals("Леонидович", p.following(listOf("Андрей"), capital = false).first())
        assertEquals("МГУ", p.following(listOf("на", "физфак"), capital = false).first())
        assertEquals("Леонидович", p.complete("лео", listOf("Андрей")).first())
        assertEquals("МГУ", p.complete("мг", listOf("физфак")).first())
        // the table's own written forms (в Москве: the case after в, capitalized)
        assertTrue(p.complete("моск", listOf("в")).take(3).all { it.startsWith("Моск") })
        p.clearLearned()
    }

    /** After a word nobody knows (a name), something still comes: never an empty bar. */
    @Test fun neverNothingAfterASpace() {
        val p = loaded(); p.clearLearned()
        assertTrue(p.following(listOf("Зюзя"), capital = false).size >= 5)
    }

    /** Chinese before a Russian word is not part of it. */
    @Test fun chineseIsNotAWordLetter() {
        assertEquals("Анд" to emptyList<String>(), WordPredictor.split("这是Анд"))
        assertEquals("" to listOf("Андрей"), WordPredictor.split("我和Андрей "))
    }

    @Test fun english() {
        val p = loaded("en"); p.clearLearned()
        assertTrue(p.following(listOf("how", "are"), capital = false).first() == "you")
        assertEquals("I", p.following(emptyList(), capital = true).let { l -> l.first { it.equals("i", true) } })
        assertTrue(p.complete("tha", emptyList()).take(3).any { it == "thank" || it == "that" })
        assertEquals("Tom", p.complete("to", listOf("with")).first { it.startsWith("To") })
    }

    /** A field: an editable text the service writes into through a real input connection. */
    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    private fun shot(view: View, name: String) {
        val w = ctx.resources.displayMetrics.widthPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val column = (view as android.view.ViewGroup).getChildAt(0)
        val b = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(b))
        val out = android.graphics.Bitmap.createBitmap(b, 0, column.top.coerceAtLeast(0), b.width, (column.bottom - column.top.coerceAtLeast(0)).coerceAtLeast(1))
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/$name.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle(); b.recycle()
    }

    @Test fun typingRussianThroughTheService() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "ru,zh") }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        // the connection the framework would hand over when a field gains focus
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
            .apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        val until = System.currentTimeMillis() + 10_000
        fun idle() { Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30)) }
        fun type(s: String) {
            for (ch in s) {
                if (ch == ' ') ime.onFunction(Key(fn = "space"))
                else { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
                idle()
            }
        }
        @Suppress("UNCHECKED_CAST")
        fun predictions() = KkImeService::class.java.getDeclaredField("predictions").apply { isAccessible = true }.get(ime) as List<String>
        // the tables load in the background on first use
        type("п"); while (predictions().isEmpty() && System.currentTimeMillis() < until) { idle(); ime.onFunction(Key(fn = "backspace")); type("п") }
        type("ри")
        val i = predictions().indexOfFirst { it.equals("привет", ignoreCase = true) }
        assertTrue("${predictions()}", i >= 0)
        ime.onCandidate(-1 - i); idle()
        assertEquals("привет ", field.text.toString().lowercase())
        // punctuation after a picked word goes before its space
        type(","); assertEquals("привет, ", field.text.toString().lowercase())
        type("я ")
        assertTrue("next words after я: ${predictions()}", predictions().take(4).contains("не"))
        shot(view, "ru_next")
        type("хо")
        shot(view, "ru_complete")
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
    }
}
