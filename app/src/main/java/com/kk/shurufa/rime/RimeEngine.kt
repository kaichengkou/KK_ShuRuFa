package com.kk.shurufa.rime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kk.shurufa.BuildConfig
import java.io.File
import java.util.concurrent.Executors

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

    private val exec = Executors.newSingleThreadExecutor()
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
            try {
                val shared = sharedDir(ctx)
                val user = userDir(ctx).apply { mkdirs() }
                val marker = File(shared, ".kk_version")
                val version = "${BuildConfig.VERSION_CODE}-${BuildConfig.VERSION_NAME}"
                val fresh = !marker.isFile || marker.readText() != version
                if (fresh) {
                    shared.deleteRecursively()
                    copyAssets(ctx, "rime", shared)
                    marker.writeText(version)
                }
                emit(State.DEPLOYING)
                RimeBridge.startup(shared.absolutePath, user.absolutePath, fresh || redeploy)
                RimeBridge.joinMaintenance()
                RimeBridge.selectSchema("rime_ice")
                emit(State.READY)
            } catch (t: Throwable) {
                Log.e("KK", "Rime start failed", t)
                error = t.message
                emit(State.FAILED)
            }
        }
    }

    fun redeploy(context: Context) {
        if (state == State.READY) exec.execute { runCatching { RimeBridge.shutdown() } }
        state = State.IDLE
        start(context, redeploy = true)
    }

    private fun copyAssets(ctx: Context, path: String, dest: File) {
        val list = ctx.assets.list(path) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> dest.outputStream().use { i.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssets(ctx, "$path/$name", File(dest, name))
    }

    val ready get() = state == State.READY
}
