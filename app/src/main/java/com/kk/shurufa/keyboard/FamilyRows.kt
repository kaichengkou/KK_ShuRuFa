package com.kk.shurufa.keyboard

/**
 * Bottom rows of the number/symbol family. They mirror the letters' bottom row: the key that
 * opened the panel (!#1, bottom left) is where ABC is, the key beside it switches 数字 ⇄ 符号,
 * and space and enter keep their places. Row widths add up to the letters' 10.5 units.
 */
object FamilyRows {
    private fun punct(zh: Boolean): Pair<Key, Key> = if (zh)
        Key(main = Output.of("，"), style = KeyStyle.SOFT, flicks = mapOf(Dir.UP to Output.of("、"))) to
            Key(main = Output.of("。"), style = KeyStyle.SOFT, flicks = mapOf(Dir.UP to Output.of("？")))
    else Key(main = Output.of(","), style = KeyStyle.SOFT, flicks = mapOf(Dir.UP to Output.of("!"))) to
        Key(main = Output.of("."), style = KeyStyle.SOFT, flicks = mapOf(Dir.UP to Output.of("?")))

    /** 数字 ⇄ 符号: wider than its neighbours and tinted, so it is easy to find and hit. */
    private fun symbolsKey() = Key(fn = "more_symbols", label = "符号", width = 1.5f, style = KeyStyle.TINT)

    fun number(zh: Boolean): List<Key> {
        val (comma, period) = punct(zh)
        return listOf(
            Key(fn = "abc", label = "ABC", width = 1.5f), symbolsKey(),
            comma, Key(fn = "space", width = 4f), period, Key(fn = "enter", width = 1.5f),
        )
    }

    /**
     * Six keys like the other rows. Moving the cursor needs no arrow keys: sliding on space moves
     * it (the academic panel sets that), so ⇥ and ⌫ get full-size keys.
     */
    fun academic(): List<Key> = listOf(
        Key(fn = "abc", label = "ABC", width = 1.5f), symbolsKey(),
        Key(fn = "space", label = "‹  空格  ›", width = 3f),
        Key(fn = "next_slot", width = 1.5f), Key(fn = "backspace", width = 1.5f), Key(fn = "enter", width = 1.5f),
    )

    fun symbols(): List<Key> = listOf(
        Key(fn = "abc", label = "ABC", width = 1.5f), Key(fn = "num", label = "123", width = 1.5f, style = KeyStyle.TINT),
        Key(fn = "lock"), Key(fn = "space", width = 4f), Key(fn = "backspace"), Key(fn = "enter", width = 1.5f),
    )
}
