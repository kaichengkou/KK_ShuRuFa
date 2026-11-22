package com.kk.shurufa.ime

import android.content.Context
import com.kk.shurufa.rime.RimeEngine
import java.io.File

/**
 * 不再出现: words the person never wants offered. They are dropped from the candidates by a Lua
 * filter (kk_muted, which reads this file when Rime's engine starts) and from the 联想 here; the
 * dictionaries themselves stay whole, and a word taken off the list comes back.
 */
object Muted {
    const val FILE = "kk_muted.txt"
    @Volatile private var words: Set<String>? = null

    fun file(ctx: Context) = File(RimeEngine.userDir(ctx), FILE)

    fun all(ctx: Context): Set<String> = words ?: synchronized(this) {
        words ?: runCatching { file(ctx).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet()).also { words = it }
    }

    fun has(ctx: Context, w: String) = w in all(ctx)

    fun add(ctx: Context, w: String) = write(ctx, all(ctx) + w)
    fun remove(ctx: Context, w: String) = write(ctx, all(ctx) - w)

    /** Merges a list from a backup. */
    fun merge(ctx: Context, text: String) = write(ctx, all(ctx) + text.lines().map { it.trim() }.filter { it.isNotEmpty() })

    private fun write(ctx: Context, set: Set<String>) {
        words = set
        file(ctx).apply { parentFile?.mkdirs() }.writeText(set.sorted().joinToString("\n", postfix = if (set.isEmpty()) "" else "\n"))
    }
}
