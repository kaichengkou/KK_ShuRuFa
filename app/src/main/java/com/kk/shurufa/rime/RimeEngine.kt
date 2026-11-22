package com.kk.shurufa.rime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kk.shurufa.ime.Background
import java.io.File

/**
 * Owns the Rime runtime: copies the bundled schema data on first run (or after an update),
 * deploys it in the background, and exposes the composition after each key.
 */
object RimeEngine {
    enum class State { IDLE, PREPARING, DEPLOYING, READY, FAILED }

    @Volatile var state = State.IDLE
        private set
    var error: String? = null
        private set

    /** Deployment and shutdown, one after another; the thread goes away when idle. */
    private val exec = Background.serial("kk-rime", android.os.Process.THREAD_PRIORITY_DEFAULT)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(State) -> Unit>()

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    private fun emit(s: State) {
        state = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun sharedDir(ctx: Context) = File(ctx.filesDir, "rime/shared")
    fun userDir(ctx: Context) = File(ctx.filesDir, "rime/user")

    /** Starts Rime if it is not running. [redeploy] forces a full rebuild of the dictionaries. */
    fun start(context: Context, redeploy: Boolean = false) {
        if (!redeploy && (state == State.READY || state == State.PREPARING || state == State.DEPLOYING)) return
        val ctx = context.applicationContext
        emit(State.PREPARING)
        exec.execute {
            redeployQueued = false
            try {
                val shared = sharedDir(ctx)
                val user = userDir(ctx).apply { mkdirs() }
                // only the files whose content changed are copied: copying the same files again
                // (new dates) is what made every update deploy for a minute
                val fresh = RimeData.sync(shared, ctx.assets.open("rime/${RimeData.MANIFEST}").use { RimeData.parse(it.reader().readText()) }) { ctx.assets.open("rime/$it") }
                writeUserConfig(ctx, shared, user)
                emit(State.DEPLOYING)
                // a restored backup asks for a full deploy (its dictionaries and phrases)
                val mark = File(user, com.kk.shurufa.Backup.REDEPLOY_MARK)
                val restored = mark.isFile
                RimeBridge.startup(shared.absolutePath, user.absolutePath, fresh || redeploy || restored)
                RimeBridge.joinMaintenance()
                mark.delete()
                // …and what it learned is merged in through Rime's own sync
                val restoreDir = File(user, "sync/${com.kk.shurufa.Backup.RESTORE_DIR}")
                if (restoreDir.isDirectory) {
                    runCatching { RimeBridge.syncUserData() }
                    restoreDir.deleteRecursively()
                }
                RimeBridge.selectSchema("rime_ice")
                emit(State.READY)
            } catch (t: Throwable) {
                Log.e("KK", "Rime start failed", t)
                error = t.message
                emit(State.FAILED)
            }
        }
    }

    private val soon = Runnable { pendingCtx?.let { redeploy(it) }; pendingCtx = null }
    private var pendingCtx: Context? = null

    /** Redeploys a moment after the last of several quick changes (packs installed one after another), once. */
    fun redeploySoon(context: Context) {
        pendingCtx = context.applicationContext
        main.removeCallbacks(soon)
        main.postDelayed(soon, 2500)
    }

    fun redeploy(context: Context) {
        // one that is queued and not begun yet will read the newest settings anyway
        if (redeployQueued) return
        redeployQueued = true
        if (state == State.READY) exec.execute { runCatching { RimeBridge.shutdown() } }
        state = State.IDLE
        start(context, redeploy = true)
    }
    @Volatile private var redeployQueued = false

    /**
     * Runs [job] on Rime's thread, after a start or deploy that is under way, and only when the
     * engine is running then (never while it deploys).
     */
    fun whenReady(job: () -> Unit) = exec.execute { if (state == State.READY) runCatching(job) }

    /** User-side patch generated from the settings; it takes precedence over the bundled one. */
    private fun writeUserConfig(ctx: Context, shared: File, user: File) {
        val prefs = com.kk.shurufa.Prefs(ctx)
        // 导入词库: one merged dictionary (雾凇 first, then the imports), or none
        val merged = ImportedDicts.merged(ctx, shared, user)
        val mergedFile = File(user, "kk_merged.dict.yaml")
        if (merged != null) { if (!mergedFile.isFile || mergedFile.readText() != merged) mergedFile.writeText(merged) } else mergedFile.delete()
        val extra = (if (merged != null) "  # 导入词库\n" + ImportedDicts.PATCH else "") +
            (if (prefs.grammar && Grammar.installed(ctx)) "  # 语法模型\n" + Grammar.patch else "")
        val patch = (
            """
            |# Generated by KK输入法 from its settings; edits here are overwritten.
            |patch:
            |  menu/page_size: 10
            |  translator/enable_correction: ${prefs.zhCorrection}
            |  # 雾凇's own typo spellings (zih → zhi, lng → lang …) count as plain spellings, with no
            |  # penalty, so they take abbreviations for typos (zihp → 制品, not z+i+h+p); KK's corrector
            |  # handles those slips with context and a penalty, and goes quiet with 按键纠错 off.
            |  # Kept: 超级简拼 and the ü/v forms.
            |  speller/algebra:
            |    - erase/^hm$/
            |    - erase/^m$/
            |    - erase/^n$/
            |    - erase/^ng$/
            |    - abbrev/^([a-z]).+$/$1/
            |    - abbrev/^([zcs]h).+$/$1/
            |    - derive/^([nl])ve$/$1ue/
            |    - derive/^([jqxy])u/$1v/
            |    - derive/^([nl])ue$/$1ve/
            |    - derive/^([jqxy])v/$1u/
            |  engine/filters/@before 0: lua_filter@*kk_typo_mark
            |  engine/filters/+:
            |    - lua_filter@*kk_emoji_order
            |    - lua_filter@*kk_muted
            |""".trimMargin() + extra + if (prefs.packPoetry) """
            |  # 扩展词库：古诗文
            |  schema/dependencies/+:
            |    - kk_poetry
            |  engine/translators/+:
            |    - table_translator@kk_poetry
            |  kk_poetry:
            |    dictionary: kk_poetry
            |    enable_sentence: false
            |    enable_completion: true
            |    initial_quality: 1.1
            |""".trimMargin() + "\n" else ""
        )
        // rewritten only when the settings changed it: a new date alone sends Rime checking
        val patchFile = File(user, "rime_ice.custom.yaml")
        if (!patchFile.isFile || patchFile.readText() != patch) patchFile.writeText(patch)
        val phrases = File(user, "custom_phrase.txt")
        if (!phrases.exists()) runCatching {
            ctx.assets.open("rime/custom_phrase.txt").use { i -> phrases.outputStream().use { i.copyTo(it) } }
        }
    }

    fun customPhraseFile(ctx: Context) = File(userDir(ctx), "custom_phrase.txt")

    val ready get() = state == State.READY
}
