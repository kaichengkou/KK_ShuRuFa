package com.kk.shurufa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.KkImeService
import com.kk.shurufa.ime.Panel
import com.kk.shurufa.ime.Sink
import com.kk.shurufa.keyboard.Key
import org.junit.Assert.assertEquals
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

/**
 * The whole input method in one piece: the service is created, its input view built, and every
 * panel and query opened through the same entry points the keys and the toolbar use. Each state
 * is rendered to build/screens/ime_*.png, in the light and the dark theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImeServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun idle(ms: Long = 50) {
        // background loading posts back to the main thread
        repeat(4) { Thread.sleep(ms / 4 + 1); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 4 + 1)) }
    }

    private fun start(theme: String): Pair<KkImeService, View> {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.THEME, theme) }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        idle(200)
        return ime to view
    }

    private fun shot(view: View, name: String, whole: Boolean = false) {
        val w = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        // the window is taller than the keyboard (room for popups); keep what is drawn
        val column = (view as ViewGroup).getChildAt(0)
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(b))
        val top = if (whole) (column.top - (ctx.resources.displayMetrics.density * 60).toInt()).coerceAtLeast(0) else column.top.coerceAtLeast(0)
        val out = Bitmap.createBitmap(b, 0, top, b.width, (column.bottom - top).coerceAtLeast(1))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tour(theme: String) {
        val (ime, view) = start(theme)
        val tag = "ime_${theme}"
        assertEquals(Panel.LETTERS, ime.panel)
        shot(view, "${tag}_letters")
        for ((tool, panel) in listOf("emoji" to Panel.EMOJI, "clipboard" to Panel.CLIPBOARD, "quick" to Panel.QUICK,
            "cursor" to Panel.CURSOR, "mic" to Panel.VOICE, "ai" to Panel.AI)) {
            ime.onTool(tool); idle()
            assertEquals(tool, panel, ime.panel)
            shot(view, "${tag}_$tool")
            // a second tap on the same button closes it again
            ime.onTool(tool); idle()
            assertEquals(Panel.LETTERS, ime.panel)
        }
        // !#1 opens the number pad; 符号 and 学术 from there
        ime.onFunction(Key(fn = "symbols")); idle()
        assertEquals(Panel.NUM, ime.panel)
        shot(view, "${tag}_num")
        ime.onFunction(Key(fn = "more_symbols")); idle()
        assertEquals(Panel.SYMBOLS, ime.panel)
        shot(view, "${tag}_symbols")
        ime.onFunction(Key(fn = "sci")); idle()
        assertEquals(Panel.MATH, ime.panel)
        shot(view, "${tag}_math")
        ime.onFunction(Key(fn = "simple")); idle()
        ime.onFunction(Key(fn = "abc")); idle()
        assertEquals(Panel.LETTERS, ime.panel)
        // the three queries: translation, emoji search, an AI instruction
        ime.onTool("translate"); idle()
        assertEquals(Sink.TRANSLATE, ime.sink)
        shot(view, "${tag}_translate")
        ime.onTool("translate"); idle()
        assertNull(ime.sink)
        ime.onTool("emoji"); idle()
        ime.openSink(Sink.EMOJI, "开心"); idle()
        assertEquals(Sink.EMOJI, ime.sink)
        assertEquals(Panel.LETTERS, ime.panel)
        shot(view, "${tag}_emoji_search")
        ime.onDismissSuggestions(); idle()
        assertNull(ime.sink)
        assertEquals("closing the search goes back to the emoji panel", Panel.EMOJI, ime.panel)
        ime.onTool("ai"); idle()
        ime.openSink(Sink.AI); idle()
        assertEquals(Sink.AI, ime.sink)
        shot(view, "${tag}_ai_instruction")
        ime.onDismissSuggestions(); idle()
        assertEquals(Panel.AI, ime.panel)
        // a notice floats above the keyboard; the window's transparent top is kept in this shot
        ime.notice("已复制"); idle(100)
        shot(view, "${tag}_notice", whole = true)
        // a panel switch while a query is open closes the query
        ime.openSink(Sink.TRANSLATE); idle()
        ime.onTool("clipboard"); idle()
        assertNull(ime.sink)
        assertEquals(Panel.CLIPBOARD, ime.panel)
        // finishing the field drops everything open
        ime.onFinishInputView(true); idle()
        ime.onDestroy()
        assertTrue(File("build/screens/${tag}_letters.png").isFile)
    }

    @Test fun lightTour() = tour("light")

    @Test fun darkTour() = tour("dark")
}
