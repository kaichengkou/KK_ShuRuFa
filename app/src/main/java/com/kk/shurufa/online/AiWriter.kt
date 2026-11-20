package com.kk.shurufa.online

/**
 * AI 改写: the actions on the AI panel and the prompts they send.
 *
 * The text is always the user's own writing, and the user writes as himself: a man, in the
 * first person. Where a language marks the speaker's gender (Russian past tense and short
 * adjectives, French and Spanish agreement, …) the masculine forms are used.
 */
object AiWriter {
    class Action(val id: String, val title: String, val group: String, val instruction: String, val diff: Boolean)

    val ACTIONS = listOf(
        Action("polish", "润色", "改写",
            "Polish the text: make it read smoothly and naturally, fix clumsy wording and rhythm, and keep the meaning, the tone and the length about the same.", true),
        Action("fix", "纠错", "改写",
            "Correct only mistakes: typos, wrong characters (for Chinese, wrong homophones from pinyin input), grammar, agreement and punctuation. Do not reword anything that is already correct.", true),
        Action("formal", "正式", "改写",
            "Rewrite the text in a polite, formal register suitable for work messages, email or official requests. Keep every point.", false),
        Action("casual", "口语", "改写",
            "Rewrite the text the way the writer would say it to a friend: relaxed, warm and natural. Keep every point.", false),
        Action("shorter", "精简", "改写",
            "Make the text shorter and clearer. Remove repetition and filler, keep every important point.", false),
        Action("longer", "扩写", "改写",
            "Expand the text so it reads fuller and more complete, adding natural connecting detail and explanation, but no new facts.", false),
        Action("to_en", "英文", "翻译", "Translate the text into natural, idiomatic English.", false),
        Action("to_ru", "俄语", "翻译", "Translate the text into natural, idiomatic Russian.", false),
        Action("to_zh", "中文", "翻译", "Translate the text into natural, idiomatic Simplified Chinese.", false),
    )

    /** 输入指令: what the user typed becomes the instruction. */
    fun custom(instruction: String) = Action("custom", "指令", "指令", instruction, true)

    private const val WRITER =
        "The text is the user's own writing. The user is a man and writes in the first person; when a language marks the " +
            "speaker's gender (Russian past tense and short adjectives, French or Spanish agreement, and so on), use masculine " +
            "forms for the writer unless the text itself clearly says otherwise. "

    const val SYSTEM = "You are the writing assistant built into the user's phone keyboard. " + WRITER +
        "You receive the text and one instruction. Return only the finished text: no quotes, no explanation, no " +
        "alternatives, no markdown. Keep the original language unless the instruction asks for a translation. Keep line " +
        "breaks, emoji, names, numbers, links and @mentions exactly as they are, and never add facts that are not in the text."

    fun prompt(a: Action, text: String) = "Instruction: ${a.instruction}\n\nText:\n$text"

    /** For translations (the 翻译 strip, voice translation): natural, same register, masculine first person. */
    fun translateSystem(from: String, to: String) =
        "You are the translator built into the user's phone keyboard. Translate the user's text from $from into $to. " + WRITER +
            "Translate meaning, not words: sound like a native speaker writing the same message, in the same register " +
            "(casual stays casual, formal stays formal). Reply with the translation only: no quotes, notes or alternatives. " +
            "Keep line breaks, emoji, names, numbers and links."
}
