package com.kk.shurufa.keyboard

import android.content.SharedPreferences
import android.graphics.RectF
import com.kk.shurufa.ime.Background

/**
 * 落点自适应: where this person actually touches each letter key, relative to its center. Thumbs
 * land toward the hand they come from, and differently on each key; a tap near the edge between
 * two keys of a row is read against where taps on each of them really land, so the line between
 * neighbours moves to where this person's taps divide.
 *
 * Only taps that stood are learned: a tap followed at once by backspace was a mistake and is
 * dropped. Offsets are learned as running means (fast at first, then slowly), count for little
 * until a key has some taps, and never move a key's center more than [MAX_SHIFT] of its size.
 */
class TouchModel(private val store: SharedPreferences) {
    private class Stat(var dx: Float = 0f, var dy: Float = 0f, var n: Int = 0)

    private val stats = HashMap<String, Stat>()
    private var pending: Triple<String, Float, Float>? = null
    private var pendingAt = 0L
    private var unsaved = 0

    /** 清除 in the settings removes the stored model; forget it here too. */
    private val cleared = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == KEY && sp.getString(KEY, null) == null) { stats.clear(); pending = null; unsaved = 0 }
    }

    init {
        store.registerOnSharedPreferenceChangeListener(cleared)
        store.getString(KEY, null)?.split(';')?.forEach { item ->
            val p = item.split(',')
            if (p.size == 4) runCatching { stats[p[0]] = Stat(p[1].toFloat(), p[2].toFloat(), p[3].toInt()) }
        }
    }

    /** The center taps on [label] land on, in [r]'s own size: its center moved by what was learned. */
    fun center(label: String, r: RectF, out: FloatArray) {
        val s = stats[label]
        val trust = if (s == null) 0f else (s.n / FULL_TRUST.toFloat()).coerceAtMost(1f)
        out[0] = r.centerX() + (s?.dx ?: 0f) * trust * r.width()
        out[1] = r.centerY() + (s?.dy ?: 0f) * trust * r.height()
    }

    /** A tap on [label] at ([x], [y]) in [r]; learned once the next key shows it was meant. */
    fun tapped(label: String, r: RectF, x: Float, y: Float, now: Long) {
        commitPending()
        val dx = ((x - r.centerX()) / r.width()).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        val dy = ((y - r.centerY()) / r.height()).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        pending = Triple(label, dx, dy); pendingAt = now
    }

    /** Backspace right after a tap: that tap was a mistake, not a sample. */
    fun undone(now: Long) { if (now - pendingAt < UNDO_MS) pending = null else commitPending() }

    private fun commitPending() {
        val (label, dx, dy) = pending ?: return
        pending = null
        val s = stats.getOrPut(label) { Stat() }
        s.n = (s.n + 1).coerceAtMost(1_000_000)
        // a plain mean for the first taps, then a slow running mean that follows a change of grip
        val a = maxOf(1f / s.n, 0.03f)
        s.dx += (dx - s.dx) * a; s.dy += (dy - s.dy) * a
        if (++unsaved >= 40) save()
    }

    fun save() {
        if (unsaved == 0) return
        unsaved = 0
        val text = stats.entries.joinToString(";") { (k, s) -> "$k,${"%.4f".format(s.dx)},${"%.4f".format(s.dy)},${s.n}" }
        Background.run { store.edit().putString(KEY, text).apply() }
    }

    companion object {
        const val KEY = "seen_touch_model"
        /** How far a learned center may sit from the key's own, as a share of its size. */
        const val MAX_SHIFT = 0.35f
        /** Taps a key needs before its offset counts in full. */
        const val FULL_TRUST = 40
        private const val UNDO_MS = 1500L
    }
}
