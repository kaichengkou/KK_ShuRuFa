package com.kk.shurufa.ime

import java.time.LocalDate

/**
 * 二十四节气: the day each solar term falls on, by the 寿星 formula for the 21st century (right
 * for nearly every year; a rare term can be a day off). The keyboard names the term on its day.
 */
object SolarTerms {
    private val NAMES = listOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
        "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )
    private val C = doubleArrayOf(
        5.4055, 20.12, 3.87, 18.73, 5.63, 20.646, 4.81, 20.1, 5.52, 21.04, 5.678, 21.37,
        7.108, 22.83, 7.5, 23.13, 7.646, 23.042, 8.318, 23.438, 7.438, 22.36, 7.18, 21.94,
    )
    /** The day of [term] (0 = 小寒) in [year], 2000–2099. */
    fun day(year: Int, term: Int): LocalDate {
        val y = year % 100
        // the first four terms belong to the start of the year: their leap correction lags a year
        val leaps = if (term < 4) (y - 1) / 4 else y / 4
        val d = (y * 0.2422 + C[term]).toInt() - leaps
        return LocalDate.of(year, term / 2 + 1, d)
    }

    /** The term that falls on [date], or null. */
    fun on(date: LocalDate = LocalDate.now()): String? {
        if (date.year !in 2000..2099) return null
        val m = date.monthValue
        for (t in listOf((m - 1) * 2, (m - 1) * 2 + 1)) if (day(date.year, t) == date) return NAMES[t]
        return null
    }
}
