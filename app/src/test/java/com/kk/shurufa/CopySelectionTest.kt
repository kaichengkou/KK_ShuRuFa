package com.kk.shurufa

import android.content.ClipboardManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 复制 / 剪切 on a selection that was there before the keyboard opened, without 选择 first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopySelectionTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A field that, like some apps, ignores the copy request: only the keyboard can copy here. */
    private class Field(view: View, s: String, a: Int, b: Int) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder(s).also { Selection.setSelection(it, a, b) }
        override fun getEditable(): Editable = text
        override fun performContextMenuAction(id: Int) = false
    }

    private fun start(inputType: Int): Pair<KkImeService, Field> {
        Prefs(ctx).put(Prefs.ANIMATIONS, false)
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view, "明天下午三点见", 0, 4)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { this.inputType = inputType; initialSelStart = 0; initialSelEnd = 4; packageName = "test.app" }, false)
        shadowOf(Looper.getMainLooper()).idle()
        return ime to field
    }

    @Test fun copiesAndCutsWhatIsSelected() {
        val (ime, field) = start(InputType.TYPE_CLASS_TEXT)
        assertTrue("the selection counts from the start", ime.editor.hasSelection)
        assertEquals("明天下午", ime.editor.copySelection(cut = false))
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        assertEquals("明天下午", cm.primaryClip?.getItemAt(0)?.text.toString())
        assertEquals("明天下午", ime.editor.copySelection(cut = true))
        assertEquals("三点见", field.text.toString())
        ime.onDestroy()
    }

    @Test fun notFromPasswords() {
        val (ime, _) = start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertNull(ime.editor.copySelection(cut = false))
        ime.onDestroy()
    }
}
