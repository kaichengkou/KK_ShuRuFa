package com.kk.shurufa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kk.shurufa.keyboard.Icons
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Each icon's tap motion as a film strip (build/screens/icon_motion.png); each ends as it rests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconMotionTest {
    private val names = listOf("emoji", "translate", "clipboard", "cursor", "ai", "mic", "quick", "settings", "undo", "backspace", "enter", "shift", "language")
    private val times = listOf(0f, 0.1f, 0.2f, 0.3f, 0.45f, 0.6f, 0.8f, 0.95f, 1f)

    private fun frame(name: String, t: Float, cell: Int): IntArray {
        val b = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        Icons.draw(c, name, cell / 2f, cell / 2f, cell * 0.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 50, 60) }, t = t)
        return IntArray(cell * cell).also { b.getPixels(it, 0, cell, 0, 0, cell, cell); b.recycle() }
    }

    @Test fun filmStrip() {
        val cell = 96
        val out = Bitmap.createBitmap(cell * times.size, cell * names.size, Bitmap.Config.ARGB_8888)
        names.forEachIndexed { r, n ->
            times.forEachIndexed { col, t -> out.setPixels(frame(n, t, cell), 0, cell, col * cell, r * cell, cell, cell) }
            // the motion starts and ends on the icon at rest, so nothing jumps
            val rest = frame(n, 1f, cell)
            for (t in listOf(0f, 0.999f)) {
                val f = frame(n, t, cell)
                val off = rest.indices.count { kotlin.math.abs(Color.red(rest[it]) - Color.red(f[it])) > 60 }
                assertTrue("$n at $t differs from rest in $off pixels", off < cell * cell / 200)
            }
        }
        File("build/screens").mkdirs()
        File("build/screens/icon_motion.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
