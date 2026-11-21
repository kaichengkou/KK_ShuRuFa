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
        b.recycle()
        activity.finish()
    }

    /** The launcher icon as a launcher draws it (a circle mask), large and small, and its themed layer. */
    @Test fun icon() {
        val d = ctx.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        val sizes = listOf(432, 192, 96)
        val out = Bitmap.createBitmap(sizes.sum() + 40 * sizes.size + 432, 432, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFDDE1E6.toInt())
        var x = 0f
        val clip = android.graphics.Path()
        for (sz in sizes) {
            c.save(); c.translate(x, (432 - sz) / 2f)
            clip.reset(); clip.addCircle(sz / 2f, sz / 2f, sz / 2f, android.graphics.Path.Direction.CW)
            c.clipPath(clip)
            d.setBounds((-sz * 0.25f).toInt(), (-sz * 0.25f).toInt(), (sz * 1.25f).toInt(), (sz * 1.25f).toInt())
            d.background.setBounds(d.bounds); d.background.draw(c)
            d.foreground.setBounds(d.bounds); d.foreground.draw(c)
            c.restore()
            x += sz + 40
        }
        // themed: the monochrome layer tinted on a tonal disc
        c.save(); c.translate(x, 0f)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCFE6E8.toInt() }
        c.drawCircle(216f, 216f, 216f, p)
        val mono = d.monochrome!!
        mono.setBounds(-108, -108, 540, 540)
        mono.setTint(0xFF0E4A50.toInt())
        mono.draw(c)
        c.restore()
        File("build/screens").mkdirs()
        File("build/screens/icon.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pages() {
        for (theme in listOf("light", "dark")) for (page in listOf("home", "look", "online", "packs", "gestures")) render(page, theme)
    }
}
