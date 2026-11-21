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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 盘古之白 through the service, into a real field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanguTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    private fun run(lang: String, on: Boolean, start: String, type: String): String {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, lang); put(Prefs.PANGU, on) }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
            .apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        field.commitText(start, 1)
        for (ch in type) {
            // a digit is a flick, not a key's main character
            val k = if (ch.isDigit()) Key(main = Output.of("x")) else Key(main = Output.of(ch.toString()))
            ime.onOutput(k, if (ch.isDigit()) Output.of(ch.toString()) else k.main!!)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).remove(Prefs.PANGU).apply()
        return field.text.toString()
    }

    @Test fun spacesBetweenChineseAndLatin() {
        assertEquals("拍了 3", run("zh", true, "拍了", "3"))
        assertEquals("用 iPhone", run("en,zh", true, "用", "iPhone"))
        // after a space, or with the setting off, nothing is added
        assertEquals("用 iPhone", run("en,zh", true, "用 ", "iPhone"))
        assertEquals("拍了3", run("zh", false, "拍了", "3"))
    }
}
