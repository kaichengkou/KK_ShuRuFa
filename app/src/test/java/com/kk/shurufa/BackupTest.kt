package com.kk.shurufa

import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.rime.RimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 备份与恢复: a phone's data into a file, and into another phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun roundTrip() {
        val prefs = Prefs(ctx)
        val user = RimeEngine.userDir(ctx).apply { mkdirs() }
        // phone A
        prefs.put(Prefs.PANGU, true)
        prefs.put(Prefs.KEY_HEIGHT, 52)
        prefs.put("llm_gemini_key", "secret")
        prefs.state.edit().putString("emoji_recent", "😂 👍").commit()
        File(user, "installation.yaml").writeText("installation_id: \"phone-a\"\n")
        File(user, "sync/phone-a").apply { mkdirs() }.resolve("rime_ice.userdb.txt").writeText("# Rime user dictionary\nni hao\t你好\tc=9 d=1 t=1\n")
        RimeEngine.customPhraseFile(ctx).writeText("# 自定义\n我的邮箱\tyx\t1\n")
        File(user, "kk_import1.dict.yaml").writeText("---\nname: kk_import1\n...\n")
        File(ctx.filesDir, "learned_ru.tsv").writeText("w\tкайфово\t3\n")
        File(ctx.filesDir, "clipboard.txt").writeText(listOf("1地址：某街 7 号", "0验证码 482913").joinToString("\u0002"))

        val out = ByteArrayOutputStream()
        val made = Backup.export(ctx, out)
        assertEquals(1, made.words)
        assertEquals(1, made.phrases)
        assertEquals(1, made.dicts)

        // phone B: some data of its own, which must survive
        prefs.sp.edit().clear().commit(); prefs.state.edit().clear().commit()
        prefs.put("llm_gemini_key", "b's own key")
        File(user, "sync").deleteRecursively()
        RimeEngine.customPhraseFile(ctx).writeText("别的词\tbd\t1\n")
        File(user, "kk_import1.dict.yaml").delete()
        File(ctx.filesDir, "learned_ru.tsv").delete()
        File(ctx.filesDir, "clipboard.txt").writeText("0今天的")

        val got = Backup.restore(ctx, ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, got.words)
        assertTrue(prefs.pangu)
        assertEquals(52, prefs.keyHeightDp)
        assertEquals("b's own key", prefs.sp.getString("llm_gemini_key", null))   // keys never travel
        assertEquals("😂 👍", prefs.state.getString("emoji_recent", null))
        // Rime finds A's snapshot in its sync folder at the next start, and deploys fully
        assertTrue(File(user, "sync/${Backup.RESTORE_DIR}/rime_ice.userdb.txt").readText().contains("你好"))
        assertTrue(File(user, Backup.REDEPLOY_MARK).isFile)
        // phrases and favourites are merged, not replaced
        val phrases = RimeEngine.customPhraseFile(ctx).readText()
        assertTrue(phrases.contains("别的词") && phrases.contains("我的邮箱"))
        assertTrue(File(user, "kk_import1.dict.yaml").isFile)
        assertEquals("w\tкайфово\t3\n", File(ctx.filesDir, "learned_ru.tsv").readText())
        val clips = File(ctx.filesDir, "clipboard.txt").readText()
        assertTrue(clips.contains("1地址：某街 7 号") && clips.contains("0今天的"))
        assertFalse("the history is not in a backup", clips.contains("482913"))
    }

    @Test fun refusesOtherFiles() {
        val zip = ByteArrayOutputStream().also { b -> java.util.zip.ZipOutputStream(b).use { it.putNextEntry(java.util.zip.ZipEntry("x.txt")); it.write(1) } }
        val r = runCatching { Backup.restore(ctx, ByteArrayInputStream(zip.toByteArray())) }
        assertEquals("这不是 KK输入法 的备份文件", r.exceptionOrNull()?.message)
    }
}
