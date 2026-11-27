package com.kk.shurufa.ime

import android.os.SystemClock
import com.kk.shurufa.Prefs

/**
 * The automatic touches on what is typed around punctuation: 盘古之白, 双击句号 and the marks
 * kept half-width after a digit (and made full-width again when Chinese follows them).
 * [wordsHere]: the field takes words (not a password, an address or a number).
 */
class Punctuation(private val editor: Editor, private val prefs: Prefs, private val wordsHere: () -> Boolean) {

    /**
     * 盘古之白: a space between Chinese and Latin letters or digits ("用 iPhone 拍了 3 张"), put in
     * front of [t] when the character before the caret and [t]'s first one are of the two kinds.
     * Off by default; never in 拼音模式, which spaces its own syllables.
     */
    fun pangu(t: String): String {
        if (!prefs.pangu || prefs.pinyinMode || t.isEmpty() || !wordsHere()) return t
        val prev = editor.textBefore(1).firstOrNull() ?: return t
        val first = t[0]
        fun latin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        return if ((isCjk(prev) && latin(first)) || (latin(prev) && isCjk(first))) " $t" else t
    }

    private var lastPeriodAt = 0L

    /**
     * 双击句号: the period tapped again right away becomes an ellipsis, 。。→ …… and .. → …
     * Returns the ellipsis when it made one (and the tap is used up).
     */
    fun ellipsis(t: String): String? {
        val now = SystemClock.uptimeMillis()
        val quick = now - lastPeriodAt < DOUBLE_PERIOD_MS
        lastPeriodAt = if (t == "。" || t == ".") now else 0L
        if (!prefs.doublePeriodEllipsis || !quick || (t != "。" && t != ".")) return null
        if (editor.textBefore(1) != t) return null
        lastPeriodAt = 0L
        val made = if (t == "。") "……" else "…"
        editor.replaceBefore(1, made)
        return made
    }

    /** A mark made half-width after a digit (",", "，"), until something else is typed after it. */
    private var halfAfterDigit: Pair<String, String>? = null
    /** The full-width mark whose half-width form was just deleted, and when: typed again soon, it stays full. */
    private var halfUndone: String? = null
    private var halfUndoneAt = 0L

    /**
     * 数字后的标点保持半角: after a digit, Chinese punctuation that would break a number is typed
     * half-width. Deleted and typed again within a few seconds, it is the Chinese mark after all
     * (once: the next time after a digit it is half-width again).
     */
    fun smartPunct(t: String): String {
        if (!prefs.smartPunct) return t
        val undone = halfUndone
        halfUndone = null
        if (undone == t && SystemClock.uptimeMillis() - halfUndoneAt < HALF_RETYPE_MS) return t
        val before = editor.textBefore(1)
        if (before.isNotEmpty() && before[0].isDigit()) return when (t) { "。" -> "."; "：" -> ":"; "，" -> ","; else -> t }
        return t
    }

    /** [half] went in for the mark [typed]: 3, stays half-width before more digits; Chinese after it turns it back (3，好的). */
    fun typed(half: String, typed: String) { halfAfterDigit = if (half != typed) half to typed else null }

    /** Chinese going in right after such a mark: it was a sentence's after all, so it is full-width again. */
    fun restoreFullWidth(next: String) {
        val (half, full) = halfAfterDigit ?: return
        halfAfterDigit = null
        if (next.firstOrNull()?.let { isCjk(it) } != true) return
        val before = editor.textBefore(2)
        if (before.length == 2 && before[0].isDigit() && before.substring(1) == half) editor.replaceBefore(1, full)
    }

    /** A backspace is about to delete what is before the caret: a half-width mark deleted is remembered. */
    fun deleting() {
        halfAfterDigit?.let { (half, full) -> if (editor.textBefore(half.length) == half) { halfUndone = full; halfUndoneAt = SystemClock.uptimeMillis() } }
    }

    companion object {
        /** 3。 deleted and typed again within this long: the full-width mark stays. */
        const val HALF_RETYPE_MS = 4000L
        /** Two taps of the period within this make an ellipsis. */
        const val DOUBLE_PERIOD_MS = 400L
    }
}
