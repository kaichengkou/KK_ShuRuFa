package com.kk.shurufa.ime

import java.math.BigDecimal
import java.math.RoundingMode

/** 大写金额: 1234.5 → 壹仟贰佰叁拾肆元伍角, as on cheques and receipts. */
object ZhNumber {
    private const val DIGITS = "零壹贰叁肆伍陆柒捌玖"
    private val SMALL = listOf("", "拾", "佰", "仟")
    private val BIG = listOf("", "万", "亿", "万亿")

    /** The amount in words, or null when [s] is not a number this can write (up to 9999 万亿, 2 decimals). */
    fun money(s: String): String? {
        val v = s.toBigDecimalOrNull() ?: return null
        if (v.signum() < 0 || v >= BigDecimal("10000000000000000")) return null
        val cents = v.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigInteger()
        val yuan = cents.divide(100.toBigInteger()).toString()
        val jiao = cents.mod(100.toBigInteger()).toInt() / 10
        val fen = cents.mod(10.toBigInteger()).toInt()
        val sb = StringBuilder()
        if (yuan != "0") sb.append(integer(yuan)).append("元")
        when {
            jiao == 0 && fen == 0 -> sb.append(if (sb.isEmpty()) "零元整" else "整")
            else -> {
                if (jiao > 0) sb.append(DIGITS[jiao]).append("角") else if (sb.isNotEmpty()) sb.append("零")
                if (fen > 0) sb.append(DIGITS[fen]).append("分") else sb.append("整")
            }
        }
        return sb.toString()
    }

    /** 1050 → 壹仟零伍拾: groups of four digits, a 零 for each run of zeros inside. */
    private fun integer(n: String): String {
        val groups = n.reversed().chunked(4).map { it.reversed() }
        val sb = StringBuilder()
        var zero = false
        for (g in groups.indices.reversed()) {
            val part = groups[g].padStart(4, '0')
            if (part == "0000") { zero = sb.isNotEmpty(); continue }
            for ((i, ch) in part.withIndex()) {
                val d = ch - '0'
                val unit = SMALL[3 - i]
                if (d == 0) { if (sb.isNotEmpty()) zero = true; continue }
                if (zero) { sb.append('零'); zero = false }
                sb.append(DIGITS[d]).append(unit)
            }
            sb.append(BIG[g])
        }
        return sb.toString()
    }
}
