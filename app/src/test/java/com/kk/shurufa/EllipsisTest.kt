package com.kk.shurufa

import android.os.Looper
import android.os.SystemClock
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

/** 双击句号: 。。 → ……, .. → …; two slow taps stay two periods. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EllipsisTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    private fun typing(lang: String, block: (KkImeService, (String) -> Unit) -> Unit): String {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, lang) }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        block(ime) { s -> val k = Key(main = Output.of(s)); ime.onOutput(k, k.main!!) }
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
        return field.text.toString()
    }

    private fun pause(ms: Long) { SystemClock.sleep(ms); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    @Test fun chinese() {
        assertEquals("好……", typing("zh") { _, tap -> tap("好"); tap("。"); tap("。") })
        assertEquals("好。。", typing("zh") { _, tap -> tap("好"); tap("。"); pause(500); tap("。") })
    }

    @Test fun russian() {
        assertEquals("Да…", typing("ru,zh") { _, tap -> tap("Да"); tap("."); tap(".") })
    }
}
