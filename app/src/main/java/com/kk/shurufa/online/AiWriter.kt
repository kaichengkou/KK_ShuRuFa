package com.kk.shurufa.online

/** AI 改写: the actions on the AI panel and the prompt they send. */
object AiWriter {
    class Action(val id: String, val title: String, val group: String, val instruction: String, val diff: Boolean)

    val GROUPS = listOf("改写", "翻译", "回复")

    val ACTIONS = listOf(
        Action("polish", "润色", "改写", "Polish the text: make it fluent and natural, fix awkward wording, keep the meaning, tone and language.", true),
        Action("fix", "纠错", "改写", "Fix typos, wrong characters, grammar and punctuation only. Change nothing else.", true),
        Action("formal", "正式", "改写", "Rewrite the text in a formal, polite register suitable for work email or official messages. Keep the language.", false),
        Action("casual", "口语", "改写", "Rewrite the text in a relaxed, friendly, conversational register. Keep the language.", false),
        Action("shorter", "精简", "改写", "Make the text shorter and clearer while keeping every important point. Keep the language.", false),
        Action("longer", "扩写", "改写", "Expand the text with natural detail so it reads fuller, without inventing facts. Keep the language.", false),
        Action("to_en", "英文", "翻译", "Translate the text into natural English.", false),
        Action("to_ru", "俄语", "翻译", "Translate the text into natural Russian.", false),
        Action("to_zh", "中文", "翻译", "Translate the text into natural Simplified Chinese.", false),
        Action("reply", "帮我回复", "回复", "The text is a message the user received. Write one short, natural reply the user could send, in the same language as the message.", false),
        Action("reply_no", "婉拒", "回复", "The text is a message the user received. Write one short, polite reply that declines, in the same language as the message.", false),
    )

    const val SYSTEM = "You are the writing assistant inside a phone keyboard. The user sends a piece of text and one " +
        "instruction. Reply with the resulting text only: no quotes, no explanation, no preamble, no markdown. " +
        "Keep line breaks, emoji, names, numbers and links as they are unless the instruction says otherwise."

    fun prompt(a: Action, text: String) = "Instruction: ${a.instruction}\n\nText:\n$text"
}
