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
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** 临时模式: other colors, and nothing typed is learned or recorded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IncognitoTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    @Test fun learnsAndRecordsNothing() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "ru,zh") }
        val normal = KbTheme.resolve(ctx, prefs).background
        prefs.state.edit().putBoolean(Prefs.INCOGNITO, true).commit()
        assertNotEquals("its own colors", normal, KbTheme.resolve(ctx, prefs).background)
        File(ctx.filesDir, "learned_ru.tsv").delete()
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        for (ch in "Зюзюкин Зюзюкин ") {
            if (ch == ' ') ime.onFunction(Key(fn = "space")) else { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
            Thread.sleep(5); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
        ime.onWindowHidden(); Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Зюзюкин Зюзюкин ", field.text.toString())
        val learned = File(ctx.filesDir, "learned_ru.tsv")
        assertFalse("no word memory written", learned.isFile && learned.readText().contains("зюзюкин"))
        val stats = File(ctx.filesDir, "typing_stats.json")
        assertTrue("no typing data", !stats.isFile || !stats.readText().contains("Зюзюкин"))
        // the same, with the mode off: now it is remembered (so the check above means something)
        prefs.state.edit().putBoolean(Prefs.INCOGNITO, false).commit()
        shadowOf(Looper.getMainLooper()).idle()
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, true)
        for (ch in "Зюзюкин ") {
            if (ch == ' ') ime.onFunction(Key(fn = "space")) else { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
            Thread.sleep(5); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        }
        ime.onWindowHidden(); Thread.sleep(500); shadowOf(Looper.getMainLooper()).idle()
        assertTrue("remembered with the mode off", learned.isFile && learned.readText().contains("зюзюкин"))
        ime.onDestroy()
        prefs.state.edit().remove(Prefs.INCOGNITO).commit()
        prefs.sp.edit().remove(Prefs.LANGUAGES).apply()
    }
}
