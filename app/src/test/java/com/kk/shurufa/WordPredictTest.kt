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

    private fun loaded(): WordPredictor {
        val p = WordPredictor(ctx, "ru")
        p.load()
        val until = System.currentTimeMillis() + 10_000
        while (!p.ready && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("tables load", p.ready)
        return p
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
