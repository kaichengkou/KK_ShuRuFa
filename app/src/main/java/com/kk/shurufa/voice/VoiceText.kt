package com.kk.shurufa.voice

/**
 * 断句和标点: turns one recognized sentence into what goes into the field, given the text
 * already before the cursor and the pause before the sentence.
 *
 * Styles: "full" keeps the model's punctuation; "chat" drops the full stop at the end of each
 * sentence (the way people type messages); "none" removes punctuation and separates sentences
 * with spaces. With [joinComma], a short pause after a sentence that ended in 。 is treated as
 * the same sentence going on, and that 。 becomes ，.
 */
object VoiceText {
    class Edit(val deleteBefore: Int, val insert: String)

    private val zhPunct = "，。！？、；：,.!?;:"
    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    fun format(raw: String, before: String, pauseMs: Long, continuing: Boolean, style: String, joinComma: Boolean, pangu: Boolean, shortPauseMs: Long = 900): Edit {
        var t = raw.trim()
        if (t.isEmpty()) return Edit(0, "")
        val chinese = t.any { isCjk(it) }
        if (!chinese) return latin(t, before)
        var delete = 0
        val prevChar = before.lastOrNull()
        when (style) {
            "none" -> {
                t = t.map { if (it in zhPunct) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()
                if (continuing && prevChar != null && !prevChar.isWhitespace()) t = " $t"
            }
            "chat" -> {
                t = t.trimEnd('。', '.')
                // a sentence going on gets a comma, a new thought after a longer pause a space
                if (continuing && prevChar != null && prevChar !in zhPunct && !prevChar.isWhitespace())
                    t = (if (pauseMs < shortPauseMs * 2) "，" else " ") + t
            }
            else -> {
                if (joinComma && continuing && prevChar == '。' && pauseMs < shortPauseMs) { delete = 1; t = "，$t" }
            }
        }
        if (pangu) t = spaced(t)
        return Edit(delete, t)
    }

    /** English, Russian: a space between sentences, a capital after a sentence end. */
    private fun latin(t0: String, before: String): Edit {
        var t = t0
        val prev = before.trimEnd()
        val sentenceStart = prev.isEmpty() || prev.last() in ".!?。！？\n"
        if (sentenceStart) t = t.replaceFirstChar { it.uppercaseChar() }
        if (before.isNotEmpty() && !before.last().isWhitespace() && !isCjk(before.last())) t = " $t"
        return Edit(0, t)
    }

    /** A space between Chinese and Latin letters or digits (盘古之白). */
    fun spaced(s: String): String {
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0) {
                val p = s[i - 1]
                val pl = p.isLetterOrDigit() && !isCjk(p) && p.code < 0x3000
                val cl = c.isLetterOrDigit() && !isCjk(c) && c.code < 0x3000
                if ((isCjk(p) && cl) || (pl && isCjk(c))) sb.append(' ')
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
