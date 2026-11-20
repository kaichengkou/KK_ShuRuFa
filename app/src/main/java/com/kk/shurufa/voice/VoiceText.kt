package com.kk.shurufa.voice

/**
 * 断句和标点: turns one recognized utterance into what goes into the field, given the text
 * already before the cursor. Punctuation inside an utterance comes from the model, which sees
 * the whole utterance; nothing here is decided by how long the speaker paused.
 *
 * Styles: "full" keeps the model's punctuation; "chat" drops the full stop at the end (the way
 * people type messages) and joins the next utterance with a comma; "none" removes punctuation
 * and separates with spaces. [fillers] removes hesitation sounds (嗯、呃) the model wrote down.
 */
object VoiceText {
    class Edit(val deleteBefore: Int, val insert: String)

    private val zhPunct = "，。！？、；：,.!?;:"
    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    private val filler = Regex("(^|(?<=[，。！？、；：,.!?\\s]))(嗯+|呃+|额+|唔+|emm+|uh+|um+)[，、,。.\\s]*", RegexOption.IGNORE_CASE)

    /** Removes hesitation sounds standing on their own (not 嗯 in “嗯，好的” said as an answer when it is the only word). */
    fun dropFillers(t: String): String {
        val out = filler.replace(t, "").trim()
        return out.ifEmpty { t }
    }

    fun format(raw: String, before: String, continuing: Boolean, style: String, pangu: Boolean, fillers: Boolean = true): Edit {
        var t = raw.trim()
        if (fillers) t = dropFillers(t)
        if (t.isEmpty()) return Edit(0, "")
        if (!t.any { isCjk(it) }) return latin(t, before)
        val prevChar = before.lastOrNull()
        when (style) {
            "none" -> {
                t = t.map { if (it in zhPunct) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()
                if (continuing && prevChar != null && !prevChar.isWhitespace()) t = " $t"
            }
            "chat" -> {
                t = t.trimEnd('。', '.')
                if (continuing && prevChar != null && prevChar !in zhPunct && !prevChar.isWhitespace()) t = "，$t"
            }
        }
        if (pangu) t = spaced(t)
        return Edit(0, t)
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
