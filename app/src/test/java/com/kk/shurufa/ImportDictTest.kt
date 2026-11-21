package com.kk.shurufa

import com.kk.shurufa.rime.ImportedDicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reading word lists for 导入词库: 搜狗 .scel, text lists, and 雾凇's own table list. */
class ImportDictTest {
    /** A minimal .scel: the syllable table at 0x1540 and the words at 0x2628, as Sogou writes them. */
    private fun scel(words: List<Pair<String, List<String>>>): ByteArray {
        val syllables = words.flatMap { it.second }.distinct()
        val b = ByteArray(0x2628)
        b[0] = 0x40; b[1] = 0x15
        val head = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0x1540 + 4
        syllables.forEachIndexed { i, s ->
            val u = s.toByteArray(Charsets.UTF_16LE)
            head.putShort(p, i.toShort()); head.putShort(p + 2, u.size.toShort())
            System.arraycopy(u, 0, b, p + 4, u.size); p += 4 + u.size
        }
        val out = ByteArrayOutputStream().apply { write(b) }
        fun short(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        for ((w, py) in words) {
            short(1); short(py.size * 2)
            py.forEach { short(syllables.indexOf(it)) }
            val u = w.toByteArray(Charsets.UTF_16LE)
            short(u.size); out.write(u)
            short(10); out.write(ByteArray(10))
        }
        return out.toByteArray()
    }

    @Test fun readsScel() {
        val bytes = scel(listOf("谷胱甘肽" to listOf("gu", "guang", "gan", "tai"), "嘌呤" to listOf("piao", "ling")))
        assertTrue(ImportedDicts.Scel.isScel(bytes))
        val e = ImportedDicts.Scel.parse(bytes)
        assertEquals(listOf("谷胱甘肽" to "gu guang gan tai", "嘌呤" to "piao ling"), e.map { it.text to it.code })
        assertTrue(e.all { it.weight == ImportedDicts.WEIGHT })
    }

    /** A real cell dictionary, when one is given (not kept in the repository). */
    @Test fun readsARealScelWhenGiven() {
        val path = System.getenv("KK_SCEL") ?: return
        val e = ImportedDicts.Scel.parse(File(path).readBytes())
        println("[scel] ${e.size} words, e.g. ${e.take(5).map { it.text + "/" + it.code }}")
        assertTrue(e.size > 100)
        assertTrue(e.all { it.code!!.split(' ').size == it.text.length })
    }

    @Test fun readsText() {
        val e = ImportedDicts.Text.parse("# my words\n氪金大佬\n嘌呤 piao ling\n谷胱甘肽\tgu guang gan tai\t50\n冷门词\t5\nabc\n常用\t10000\n")
        val m = e.associateBy { it.text }
        assertEquals(setOf("氪金大佬", "嘌呤", "谷胱甘肽", "冷门词", "常用"), m.keys)
        assertNull(m.getValue("氪金大佬").code)
        assertEquals("piao ling", m.getValue("嘌呤").code)
        assertEquals("gu guang gan tai", m.getValue("谷胱甘肽").code)
        // a list's own weights keep their order but are capped, so nothing outranks everyday words
        assertTrue(m.getValue("冷门词").weight < m.getValue("谷胱甘肽").weight)
        assertEquals(ImportedDicts.MAX_WEIGHT, m.getValue("常用").weight)
    }

    @Test fun readsTheMainTables() {
        val yaml = "---\nname: rime_ice\nimport_tables:\n  - cn_dicts/8105     # 字表\n  # - cn_dicts/41448\n  - cn_dicts/base\n\n  - cn_dicts/ext\n...\n\nA\tA\n"
        assertEquals(listOf("cn_dicts/8105", "cn_dicts/base", "cn_dicts/ext"), ImportedDicts.mainTables(yaml))
    }
}
