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
import java.time.Duration

/** How often a key calls into the app's field: each call wakes the app's main thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FieldReadsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        var reads = 0
        var writes = 0
        override fun getEditable(): Editable = text
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextBeforeCursor(n, flags) }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextAfterCursor(n, flags) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes++; return super.commitText(text, newCursorPosition) }
    }

    @Test fun oneReadAKey() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "ru,zh") }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        val s = "привет, как дела? я дома. "
        field.reads = 0; field.writes = 0
        val per = StringBuilder()
        for (ch in s) {
            val r0 = field.reads
            if (ch == ' ') ime.onFunction(Key(fn = "space")) else { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
            per.append(ch).append(field.reads - r0).append(' ')
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
        }
        println("field reads per key: $per")
        assertEquals("привет, как дела? я дома. ", field.text.toString())
        // one read of the field a key at most: what a key typed is added to what was read before it
        assertTrue("reads ${field.reads} for ${s.length} keys: $per", field.reads <= s.length)
        assertEquals(s.length, field.writes)
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
    }
}
