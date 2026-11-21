package com.kk.shurufa

import com.kk.shurufa.rime.RimeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RimeDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun onlyChangedFilesAreCopied() {
        val shared = tmp.newFolder("shared")
        var files = mapOf("a.yaml" to "one", "cn_dicts/b.dict.yaml" to "two", "lua/c.lua" to "three")
        val opened = ArrayList<String>()
        fun run() = RimeData.sync(shared, files.mapValues { sha(it.value) }) { opened += it; files.getValue(it).byteInputStream() }

        assertTrue(run())
        assertEquals(3, opened.size)
        val dictTime = File(shared, "cn_dicts/b.dict.yaml").also { it.setLastModified(1_000_000) }

        // the same data again (an app update that did not touch it): nothing copied, nothing to deploy
        opened.clear()
        assertFalse(run())
        assertTrue(opened.isEmpty())

        // one file changed, one removed: only that one is copied, the dictionary keeps its date
        files = mapOf("a.yaml" to "one", "cn_dicts/b.dict.yaml" to "two", "lua/c.lua" to "four")
        opened.clear()
        assertTrue(run())
        assertEquals(listOf("lua/c.lua"), opened)
        assertEquals("four", File(shared, "lua/c.lua").readText())
        assertEquals(1_000_000L, dictTime.lastModified())

        files = mapOf("a.yaml" to "one", "cn_dicts/b.dict.yaml" to "two")
        assertTrue(run())
        assertFalse(File(shared, "lua/c.lua").exists())
    }

    @Test fun anOlderInstallIsHashedNotRecopied() {
        // what 0.12 and before left: the files, no record of their hashes
        val shared = tmp.newFolder("shared")
        File(shared, "a.yaml").writeText("one")
        File(shared, ".kk_version").writeText("37-0.12.1")
        val files = mapOf("a.yaml" to "one", "b.yaml" to "new")
        val opened = ArrayList<String>()
        assertTrue(RimeData.sync(shared, files.mapValues { sha(it.value) }) { opened += it; files.getValue(it).byteInputStream() })
        assertEquals(listOf("b.yaml"), opened)
        assertFalse(File(shared, ".kk_version").exists())
    }

    @Test fun manifestRoundTrips() {
        val m = mapOf("x/y.txt" to "ab12", "z.yaml" to "cd34")
        assertEquals(m, RimeData.parse(RimeData.format(m)))
    }
}
