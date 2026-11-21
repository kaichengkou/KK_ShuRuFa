package com.kk.shurufa

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.settings.SettingsActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** The settings app, rendered page by page to build/screens/settings_*.png in both themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRenderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun render(page: String, theme: String) {
        Prefs(ctx).put(Prefs.THEME, theme)
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra("page", page)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
        val v: View = activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File("build/screens").mkdirs()
        File("build/screens/settings_${theme}_$page.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pages() {
        for (theme in listOf("light", "dark")) for (page in listOf("home", "look", "online")) render(page, theme)
    }
}
