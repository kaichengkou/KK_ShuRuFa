package com.kk.shurufa.ime

import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.QuickPanel

/** What the 快捷面板 shows; its taps are handled by the input method. */
internal object QuickTiles {
    /** Tiles that open 雾凇's own candidates: tile → what is typed for them. */
    val RIME_TOOLS = linkedMapOf("date" to "rq", "time" to "sj", "week" to "xq", "lunar" to "nl")

    /**
     * 快捷面板, in order: typing switches, look and feedback, the tools not already on the toolbar,
     * then the keyboard itself. Panels reachable from !#1 (数学、符号) are not repeated here.
     * [isZh] and [lang]: the language in use.
     */
    fun sections(prefs: Prefs, isZh: Boolean, lang: String): List<QuickPanel.Section> {
        val themeName = when (prefs.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "自动" }
        val onBar = prefs.toolbar.toSet()
        val tools = listOf("ai" to "AI 改写", "mic" to "语音", "translate" to "翻译", "cursor" to "文字编辑", "clipboard" to "剪贴板", "emoji" to "表情")
            .filter { it.first !in onBar }
            .map { (id, name) -> QuickPanel.Tile("tool:$id", name) }
        // switches for the language in use: 中文's own three, or 俄语重音
        val langSwitches = when {
            isZh -> listOf(
                QuickPanel.Tile("trad", "繁体", on = prefs.zhTraditional),
                QuickPanel.Tile("emoji", "候选表情", on = prefs.zhEmoji),
                QuickPanel.Tile("pinyin", "拼音模式", on = prefs.pinyinMode))
            lang == "ru" -> listOf(QuickPanel.Tile("stress", "俄语重音", on = prefs.stressMode))
            else -> emptyList()
        }
        return listOf(
            QuickPanel.Section(QuickPanel.Kind.SWITCHES, "", listOf(
                QuickPanel.Tile("incognito", "临时模式", on = prefs.incognito),
                QuickPanel.Tile("predict", "联想", on = if (isZh) prefs.zhPredict else prefs.wordPredict),
            ) + (if (isZh) listOf(QuickPanel.Tile("correct", "纠错", on = prefs.zhCorrection)) else emptyList()) + langSwitches + listOf(
                QuickPanel.Tile("vibrate", "振动", on = prefs.vibrate),
                QuickPanel.Tile("sound", "按键音", on = prefs.sound),
            )),
            QuickPanel.Section(QuickPanel.Kind.ROW, "插入", listOf(
                QuickPanel.Tile("date", "日期"), QuickPanel.Tile("time", "时间"), QuickPanel.Tile("week", "星期"),
                QuickPanel.Tile("lunar", "农历"), QuickPanel.Tile("calc", "计算 · 大写"))),
            QuickPanel.Section(QuickPanel.Kind.ROW, "工具", tools),
            QuickPanel.Section(QuickPanel.Kind.FOOTER, "", listOf(
                QuickPanel.Tile("theme", "主题 · $themeName"),
                QuickPanel.Tile("toolbar", "工具栏"),
                QuickPanel.Tile("picker", "切换输入法"),
                QuickPanel.Tile("settings", "设置"))),
        )
    }
}
