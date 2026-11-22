package com.kk.shurufa

import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.KkImeService
import com.kk.shurufa.keyboard.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Backspace in editors that will not say what is before the caret (Samsung Notes and the like). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackspaceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A rich editor: it hides its text from the keyboard and refuses deletes, but takes keys. */
    private class Rich(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder("abc").also { Selection.setSelection(it, 3) }
        var keys = 0
        override fun getEditable(): Editable = text
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
        override fun deleteSurroundingText(b: Int, a: Int) = false
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int) = false
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                keys++; val e = Selection.getSelectionEnd(text); if (e > 0) text.delete(e - 1, e)
            }
            return true
        }
    }

    /** Says what is before the caret, claims a selection that is not there, and agrees to deletes it ignores. */
    private class Liar(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder("abc").also { Selection.setSelection(it, 3) }
        override fun getEditable(): Editable = text
        override fun getSelectedText(flags: Int): CharSequence = "abc"
        override fun deleteSurroundingText(b: Int, a: Int) = true
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int) = true
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) { val e = Selection.getSelectionEnd(text); if (e > 0) text.delete(e - 1, e) }
            return true
        }
    }

    @Test fun editorsThatSayYesAndDoNothing() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "en,zh") }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Liar(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "com.example.notes" }, false)
        repeat(2) { ime.onFunction(Key(fn = "backspace")); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        assertEquals("a", field.text.toString())
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
    }

    /** Like Samsung Notes: shows its text, agrees to deletes and keys and ignores them, but takes a selection replaced. */
    private class SelectOnly(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder("abc").also { Selection.setSelection(it, 3) }
        override fun getEditable(): Editable = text
        override fun deleteSurroundingText(b: Int, a: Int) = true
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int) = true
        override fun sendKeyEvent(event: KeyEvent) = true
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) =
            android.view.inputmethod.ExtractedText().apply {
                this.text = SpannableStringBuilder(this@SelectOnly.text).toString(); startOffset = 0
                selectionStart = Selection.getSelectionStart(this@SelectOnly.text); selectionEnd = Selection.getSelectionEnd(this@SelectOnly.text)
            }
    }

    /** Samsung Notes: deletes are taken into what it tells the keyboard but never reach the page; it is not even asked. */
    @Test fun editorsThatOnlyReplaceASelection() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "en,zh") }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = SelectOnly(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "com.samsung.android.app.notes" }, false)
        repeat(2) { ime.onFunction(Key(fn = "backspace")); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        assertEquals("a", field.text.toString())
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
    }

    @Test fun richEditorsGetARealKey() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "en,zh") }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Rich(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "com.example.richnotes" }, false)
        repeat(2) { ime.onFunction(Key(fn = "backspace")); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
        assertEquals("a", field.text.toString())
        assertTrue(field.keys >= 2)
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
    }
}
