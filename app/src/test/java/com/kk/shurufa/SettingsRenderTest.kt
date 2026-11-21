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

    /** 打字数据 with a month of typing, rendered as the page and as the home page's summary. */
    @Config(qualifiers = "w400dp-h1900dp-xxhdpi")
    @Test fun stats() {
        val days = org.json.JSONObject()
        val today = java.time.LocalDate.now()
        for (i in 0 until 34) {
            val n = (900 + 700 * kotlin.math.sin(i * 0.7) + (i * 137) % 500).toInt().coerceAtLeast(0)
            if (i % 9 != 4) days.put(today.minusDays(i.toLong()).toString(), org.json.JSONArray(listOf(n, n / 6, n / 10, n / 20)))
        }
        val hours = org.json.JSONArray((0 until 24).map { h -> (if (h in 8..23) 300 + 900 * kotlin.math.exp(-((h - 21.0) * (h - 21.0)) / 8) + 400 * kotlin.math.exp(-((h - 10.0) * (h - 10.0)) / 4) else 20.0).toInt() })
        val words = org.json.JSONObject()
        listOf("项目" to 120, "今天" to 96, "明天" to 80, "会议" to 66, "方案" to 60, "设计" to 55, "输入法" to 52, "谢谢" to 48, "好的" to 47, "时间" to 40,
            "问题" to 38, "下午" to 33, "客户" to 30, "数据" to 29, "测试" to 26, "周末" to 22, "晚饭" to 20, "电影" to 18, "键盘" to 17, "Kotlin" to 16,
            "朋友" to 15, "周报" to 14, "发布" to 13, "咖啡" to 12, "地铁" to 11, "需求" to 10, "文档" to 10, "评审" to 9, "上线" to 8, "附件" to 8,
            "照片" to 7, "生日" to 7, "快递" to 6, "跑步" to 6, "学习" to 5, "老师" to 5, "春天" to 4, "天气" to 4, "火锅" to 4, "旅行" to 3).forEach { (w, n) -> words.put(w, n) }
        val emoji = org.json.JSONObject().put("😂", 40).put("👍", 31).put("🙏", 18).put("❤️", 12).put("🎉", 7).put("😅", 5)
        File(ctx.filesDir, "typing_stats.json").writeText(org.json.JSONObject().put("since", today.minusDays(40).toString()).put("days", days)
            .put("hours", hours).put("words", words).put("emoji", emoji).toString())
        com.kk.shurufa.ime.TypingStats.reload()
        for (theme in listOf("light", "dark")) { render("stats", theme); render("home", theme) }
        File(ctx.filesDir, "typing_stats.json").delete()
        com.kk.shurufa.ime.TypingStats.reload()
    }

    @Test fun pages() {
        for (theme in listOf("light", "dark")) for (page in listOf("home", "look", "ai", "online", "packs")) render(page, theme)
    }
}
