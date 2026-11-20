package com.kk.shurufa.ime

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** Evaluates what was typed before "=" on the math keyboard (×, ÷, −, ^, superscripts, √, π, e, !, %). */
object Calc {
    private const val SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    fun eval(src: String): Double? {
        val s = src.replace(Regex("\\s+"), "").replace(Regex("[×·*]"), "*").replace(Regex("[÷/∶]"), "/")
            .replace(Regex("[−–]"), "-").replace(',', '.')
        if (s.isEmpty() || !Regex("[-+*/^√∛!%⁰¹²³⁴⁵⁶⁷⁸⁹]").containsMatchIn(s)) return null
        val p = Parser(s)
        return try {
            val v = p.expr()
            if (p.i == s.length && v.isFinite()) v else null
        } catch (_: Exception) { null }
    }

    fun format(r: Double): String {
        if (abs(r) < 1e-12) return "0"
        if (r == Math.rint(r) && abs(r) < 1e15) return r.toLong().toString()
        return java.math.BigDecimal(r).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
    }

    private class Parser(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'

        fun expr(): Double {
            var v = term()
            while (true) when (peek()) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                val c = peek()
                when {
                    c == '*' -> { i++; v *= unary() }
                    c == '/' -> { i++; v /= unary() }
                    c.isDigit() || c == '(' || c == '[' || c == 'π' || c == '√' || c == '∛' || c == 'e' -> v *= unary()
                    else -> return v
                }
            }
        }

        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> postfix()
        }

        fun postfix(): Double {
            var v = primary()
            while (true) {
                val c = peek()
                when {
                    c == '^' -> { i++; v = v.pow(unary()) }
                    c == '⁻' || SUP.indexOf(c) >= 0 -> {
                        var neg = false
                        if (c == '⁻') { neg = true; i++ }
                        val sb = StringBuilder()
                        while (i < s.length && SUP.indexOf(s[i]) >= 0) { sb.append(SUP.indexOf(s[i])); i++ }
                        if (sb.isEmpty()) throw IllegalArgumentException()
                        val e = sb.toString().toDouble()
                        v = v.pow(if (neg) -e else e)
                    }
                    c == '!' -> {
                        i++
                        if (v < 0 || v > 170 || v != Math.rint(v)) throw IllegalArgumentException()
                        var f = 1.0; var k = 2.0
                        while (k <= v) { f *= k; k++ }
                        v = f
                    }
                    c == '%' -> { i++; v /= 100 }
                    else -> return v
                }
            }
        }

        fun primary(): Double {
            val c = peek()
            return when {
                c == '(' || c == '[' -> {
                    i++
                    val v = expr()
                    if (peek() == ')' || peek() == ']') i++ else throw IllegalArgumentException()
                    v
                }
                c == 'π' -> { i++; Math.PI }
                c == 'e' -> { i++; Math.E }
                c == '√' -> { i++; sqrt(unary()) }
                c == '∛' -> { i++; cbrt(unary()) }
                else -> {
                    val m = Regex("^\\d+(?:\\.\\d+)?").find(s.substring(i)) ?: throw IllegalArgumentException()
                    i += m.value.length
                    m.value.toDouble()
                }
            }
        }
    }
}
