package com.kk.shurufa

import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.rime.DictPacks
import com.kk.shurufa.rime.ImportedDicts
import com.kk.shurufa.rime.RimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 词库拓展包: the catalogs, links, and what installing writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictPacksTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun builtInCatalog() {
        val c = DictPacks.builtIn(ctx)
        assertTrue(c.packs.size >= 40)
        assertEquals(c.packs.size, c.packs.map { it.id }.toSet().size)
        assertTrue(c.packs.all { it.url.startsWith("https://pinyin.sogou.com/d/dict/download_cell.php?id=") })
        assertEquals(listOf("专业", "生活", "文化", "方言", "体育", "自然"), c.packs.map { it.group }.distinct())
    }

    @Test fun subscribedCatalogs() {
        val c = DictPacks.parse("""{"name": "我的词库", "packs": [
            {"id": "a", "group": "工作", "name": "项目词", "words": 12, "url": "https://example.com/a.txt"},
            {"id": "sogou:15117", "name": "计算机"},
            {"id": "", "name": "没有编号的跳过"},
            {"id": "b", "name": "没有链接的跳过"}]}""", "https://example.com/feed.json")
        assertEquals("我的词库", c.name)
        assertEquals(listOf("a", "sogou:15117"), c.packs.map { it.id })
        assertEquals("其他", c.packs[1].group)
        assertTrue(c.packs[1].url.endsWith("id=15117&name=kk"))
    }

    @Test fun links() {
        assertTrue(DictPacks.resolve("https://pinyin.sogou.com/dict/detail/index/15125")!!.contains("id=15125"))
        assertEquals("https://example.com/w.scel", DictPacks.resolve("  https://example.com/w.scel "))
        assertNull(DictPacks.resolve("15125"))
    }

    /** Installing the same pack again replaces it; the merged dictionary lists every import after 雾凇's tables. */
    @Test fun installingAgainReplaces() {
        val user = RimeEngine.userDir(ctx).apply { mkdirs() }
        ImportedDicts.import(ctx, "项目词", "甲乙丙\n丁戊己\n".toByteArray(), source = "a")
        Thread.sleep(5)
        ImportedDicts.import(ctx, "项目词", "甲乙丙\n丁戊己\n庚辛壬\n".toByteArray(), source = "a")
        ImportedDicts.import(ctx, "别的", "子丑寅\n".toByteArray())
        val list = ImportedDicts.list(ctx)
        assertEquals(2, list.size)
        assertEquals(3, list.first { it.source == "a" }.words)
        val shared = File(ctx.filesDir, "shared-test").apply { mkdirs() }
        File(shared, "rime_ice.dict.yaml").writeText("---\nname: rime_ice\nimport_tables:\n  - cn_dicts/8105\n  - cn_dicts/base\n...\n")
        val merged = ImportedDicts.merged(ctx, shared, user)!!
        val tables = merged.lines().filter { it.startsWith("  - ") }.map { it.removePrefix("  - ") }
        assertEquals(listOf("rime_ice", "cn_dicts/8105", "cn_dicts/base") + list.map { it.id }, tables)
        list.forEach { ImportedDicts.delete(it) }
        assertNull(ImportedDicts.merged(ctx, shared, user))
    }
}
