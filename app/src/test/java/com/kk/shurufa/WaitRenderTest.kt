package com.kk.shurufa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.KkImeService
import com.kk.shurufa.keyboard.AiPanel
import com.kk.shurufa.keyboard.Motion
import com.kk.shurufa.keyboard.VoicePanel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Waiting, listening and 临时模式, rendered to build/screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaitRenderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val d get() = ctx.resources.displayMetrics.density

    private fun save(v: View, hDp: Int, name: String) {
        val w = ctx.resources.displayMetrics.widthPixels
        (v.parent as? ViewGroup)?.removeView(v)
        val f = FrameLayout(ctx); f.addView(v, FrameLayout.LayoutParams(-1, -1))
        f.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((hDp * d).toInt(), View.MeasureSpec.EXACTLY))
        f.layout(0, 0, w, (hDp * d).toInt())
        val b = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888); f.draw(Canvas(b))
        File("build/screens").mkdirs(); File("build/screens/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    private fun since(o: Any, field: String, msAgo: Long) =
        o.javaClass.getDeclaredField(field).apply { isAccessible = true }.setLong(o, Motion.now() - msAgo)

    @Test fun waits() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, true) }
        val vp = VoicePanel(ctx, prefs).apply { language = "中文"; engineName = "Gemini"; mode = "转文字"; look = VoicePanel.Look.LISTENING; status = "正在听…" }
        vp.setLevel(0.7f); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        save(vp, 220, "voice_listening")
        // the waves through a phrase: quiet, speaking, loud, then settling
        for ((i, lv) in listOf(0f, 0.35f, 0.7f, 1f, 0.6f, 0.1f).withIndex()) {
            vp.setLevel(lv)
            repeat(12) { Thread.sleep(16); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); vp.draw(Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))) }
            save(vp, 220, "voice_wave_$i")
        }
        vp.look = VoicePanel.Look.WORKING; vp.status = "正在识别…"; since(vp, "workSince", 2600)
        save(vp, 220, "voice_waiting")
        val ap = AiPanel(ctx, prefs).apply { doneTitle = "润色"; running = "polish" }
        since(ap, "runSince", 3200)
        save(ap, 230, "ai_waiting")
    }

    @Test fun floatStrip() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, false) }
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        fun field(n: String) = KkImeService::class.java.getDeclaredField(n).apply { isAccessible = true }.get(ime)
        val f = field("floats") as com.kk.shurufa.keyboard.FloatPills
        val bar = field("bar") as com.kk.shurufa.keyboard.CandidateBar
        val w = ctx.resources.displayMetrics.widthPixels
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        fun shot(name: String) {
            repeat(2) {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            val col = (view as ViewGroup).getChildAt(0)
            val top = (col.top - (110 * d).toInt()).coerceAtLeast(0)
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(android.graphics.Color.parseColor("#F7F5F0")); view.draw(this) }
            val out = Bitmap.createBitmap(b, 0, top, b.width, (col.top + (200 * d).toInt() - top).coerceAtMost(b.height - top))
            File("build/screens/$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bar.showPredictions(listOf("吧", "了", "的"))
        f.show("", false, listOf("周末一起去爬山吧", "顺便吃个饭", "你几点有空"))
        shot("float_cloud")
        org.junit.Assert.assertTrue(f.showing && f.tabRects.size == 3)
        ime.onDestroy()
    }

    @Test fun translateStrip() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, false) }
        val st = com.kk.shurufa.keyboard.TranslateStrip(ctx, prefs).apply {
            theme = com.kk.shurufa.keyboard.KbTheme.resolve(ctx, prefs)
            fromName = "自动·English"; toName = "Русский"; engineName = "Gemini"; insertable = "See you tomorrow at the station"
        }
        save(st, 136, "translate_empty")
        st.source = "See you tomorrow at the station"; st.result = "Увидимся завтра на вокзале"; st.replaces = true; st.insertable = ""; st.refresh()
        save(st, 136, "translate_done")
    }

    @Test fun incognitoKeyboard() {
        val prefs = Prefs(ctx).apply { put(Prefs.ANIMATIONS, false) }
        prefs.state.edit().putBoolean(Prefs.INCOGNITO, true).commit()
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        val w = ctx.resources.displayMetrics.widthPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val col = (view as ViewGroup).getChildAt(0)
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(b))
        val out = Bitmap.createBitmap(b, 0, col.top.coerceAtLeast(0), b.width, (col.bottom - col.top.coerceAtLeast(0)).coerceAtLeast(1))
        File("build/screens/incognito.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ime.onDestroy()
        prefs.state.edit().remove(Prefs.INCOGNITO).commit()
    }
}
