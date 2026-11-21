package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Work that must not hold up a key press: reading data files, writing small ones. Jobs run one
 * after another on a single background thread that exists only while there is work, so an idle
 * keyboard keeps no thread of its own awake.
 */
object Background {
    private val main = Handler(Looper.getMainLooper())
    private val pool = serial("kk-bg")

    /**
     * One job after another on a thread of its own, which ends after a few idle seconds. Work the
     * user is waiting for (deploying the dictionaries) keeps the normal [priority].
     */
    fun serial(name: String, priority: Int = Process.THREAD_PRIORITY_BACKGROUND): ExecutorService =
        ThreadPoolExecutor(0, 1, 3, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread({ Process.setThreadPriority(priority); r.run() }, name).apply { isDaemon = true }
        }

    fun run(job: () -> Unit) = pool.execute { runCatching(job) }

    /** Runs [job] in the background and hands its result to [done] on the main thread. */
    fun <T> load(job: () -> T, done: (T) -> Unit) = pool.execute {
        val r = runCatching(job)
        r.onSuccess { v -> main.post { done(v) } }
    }
}
