package com.kk.shurufa.ime

import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.GridPanel
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.MathLayout
import com.kk.shurufa.keyboard.MathPanel
import com.kk.shurufa.keyboard.NumPadView
import com.kk.shurufa.keyboard.Output

/**
 * 数字: the number pad (简易, with the marks the field takes and 大写金额) and the academic
 * keyboard (学术, in tabs), with 计算 after = on either. The bar shows their chips.
 */
class NumbersFeature(private val host: Host, private val bar: () -> CandidateBar) {
    private val ctx get() = host.context
    private val prefs get() = host.prefs

    /** The academic keyboard's layout, from the layout files. */
    var math: MathLayout? = null
    private var mathTab = "basic"

    /** The marks of the number pad's column for the field ([Fields.numberOps]); null in a text field. */
    var fieldOps: List<String>? = null
    val ops: List<String> get() = fieldOps ?: Fields.NUM_OPS

    lateinit var numPad: NumPadView
        private set
    lateinit var mathPanel: MathPanel
        private set

    /** 简易/科学: !#1 and number fields open the number keyboard in the mode used last. */
    fun panel() = if (prefs.numMode == "sci") Panel.MATH else Panel.NUM

    fun build() {
        mathPanel = MathPanel(ctx, prefs) { host.newKeyboard() }
        mathPanel.theme = host.kbTheme
        mathPanel.tabs.onTab = { id -> onMathTab(id) }
        mathPanel.tabs.onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
        val strip = GridPanel(ctx, prefs).apply {
            theme = host.kbTheme; cellStyle = GridPanel.CellStyle.SOFT; columns = 1; textSp = 20f
            cellPadDp = 0f; cellGapDp = prefs.keyGapDp
            onItem = { i -> ops.getOrNull(i)?.let { onOp(it) } }
            onFeedback = { host.haptics.play(HapticEvent.LETTER) }
        }
        numPad = NumPadView(ctx, host.newKeyboard(), strip)
    }

    /** The number pad with [rows] for its keys and the field's marks beside them. */
    fun showNumPad(rows: List<List<Key>>) {
        numPad.keys.setRows(rows)
        numPad.setItems(ops.map { if (it == " ") "空格" else it })
    }

    /** A key of the number pad typed [t]. */
    fun typed(t: String) {
        host.editor.type(t, pairable = t.length > 1)
        if (t == "=") showCalcResult() else bar().showResult(null)
        showMoneyChip()
    }

    private fun onOp(op: String) {
        host.finishComposing()
        host.editor.type(op, pairable = op == "(")
        if (op == "=") showCalcResult() else bar().showResult(null)
        showMoneyChip()
    }

    private fun showCalcResult() {
        if (!prefs.mathCalc) return
        val line = host.editor.textBefore(200).substringAfterLast('\n').dropLast(1)
        Calc.eval(line.substringAfterLast('='))?.let { bar().showResult(Calc.format(it)) }
    }

    /** 大写金额: after a number on the number pad, a chip offers it written out (壹佰贰拾叁元整). */
    fun showMoneyChip() {
        // a phone number, a card's digits, a code: no 大写金额 for those
        if (fieldOps != null) { bar().showMoney(null); return }
        val num = Regex("(\\d{1,16}(?:\\.\\d{1,2})?)$").find(host.editor.textBefore(24))?.value
        val before = host.editor.textBefore(26).dropLast(num?.length ?: 0)
        // not the digits of a longer expression's result or a date
        bar().showMoney(if (num != null && !before.endsWith("=") && !before.endsWith(".")) ZhNumber.money(num)?.let { num to it } else null)
    }

    fun onMoneyChip(number: String, words: String) {
        if (host.editor.textBefore(number.length) == number) host.editor.replaceBefore(number.length, words)
        bar().showMoney(null)
    }

    private fun onMathTab(id: String) {
        val m = math ?: return
        val from = m.tabs.indexOfFirst { it.id == mathTab }
        val to = m.tabs.indexOfFirst { it.id == id }
        mathTab = id
        refreshMath(slide = (to - from).coerceIn(-1, 1))
    }

    fun refreshMath(slide: Int) {
        val m = math ?: return
        if (m.tabs.none { it.id == mathTab }) mathTab = m.tabs.first().id
        mathPanel.show(m, mathTab, slide)
    }

    /** A key of the academic keyboard typed [o], as Unicode or as TeX. */
    fun mathOut(o: Output) {
        val text = if (prefs.mathFormat == "tex") o.tex else o.text
        host.editor.type(text, pairable = false)
        bar().showResult(null)
        if (o.label == "=" && prefs.mathFormat == "uni") showCalcResult()
    }
}
