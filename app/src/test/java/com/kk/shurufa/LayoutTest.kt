package com.kk.shurufa

import com.kk.shurufa.ime.Calc
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.keyboard.LayoutRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutTest {
    private val repo = LayoutRepo(null) { File("src/main/assets/layouts/$it").readText() }

    @Test fun chineseLayoutMatchesSamsung() {
        val zh = repo.keyboard("zh")
        assertEquals("rime", zh.engine)
        assertEquals(4, zh.rows.size)
        assertEquals(10, zh.rows[0].size)
        val q = zh.rows[0][0]
        assertEquals("q", q.main!!.text)
        assertEquals("1", q.flicks[Dir.UP]!!.text)
        val z = zh.rows[2].first { it.main?.text == "z" }
        assertEquals("（", z.flicks[Dir.UP]!!.text)
        assertEquals("'", zh.rows[2][0].main!!.text)
        assertEquals("backspace", zh.rows[2].last().fn)
        val comma = zh.rows[3].first { it.main?.text == "，" }
        assertTrue(comma.long.any { it.text == "、" })
    }

    @Test fun russianLayout() {
        val ru = repo.keyboard("ru")
        assertEquals(11, ru.rows[0].size)
        assertEquals("]", ru.rows[0][10].flicks[Dir.UP]!!.text)
        assertEquals("₽", ru.rows[1].first { it.main?.text == "а" }.flicks[Dir.UP]!!.text)
        assertEquals("\"", ru.rows[2].first { it.main?.text == "с" }.flicks[Dir.UP]!!.text)
        assertEquals(",", ru.rows[2].first { it.main?.text == "т" }.flicks[Dir.UP]!!.text)
        assertTrue(ru.rows[0].first { it.main?.text == "е" }.long.any { it.text == "ё" })
        assertEquals("shift", ru.rows[2][0].fn)
    }

    @Test fun otherLayoutsParse() {
        listOf("en", "phone", "cursor").forEach { assertTrue(repo.keyboard(it).rows.isNotEmpty()) }
        val phone = repo.keyboard("phone")
        assertEquals("1", phone.rows[0][0].main!!.text)
        assertEquals("0", phone.rows[3][2].main!!.text)
    }

    @Test fun mathLayout() {
        val m = repo.math()
        assertEquals(listOf("basic", "func", "calc", "rel", "lin", "greek", "phys"), m.tabs.map { it.id })
        m.tabs.forEach { t -> t.rows.forEach { r -> assertEquals(6, r.size) } }
        val frac = m.tabs[0].rows[2][0]
        assertEquals("(‸)/()", frac.main!!.text)
        assertEquals("\\frac{‸}{}", frac.main!!.tex)
        val integral = m.tabs[2].rows[0][4]
        assertEquals("\\int_{‸}^{} \\,dx", integral.flicks[Dir.UP]!!.tex)
        assertEquals(7, m.bottom.size)
    }

    @Test fun symbols() {
        val cats = repo.symbols()
        assertEquals("common", cats[0].id)
        assertTrue(cats.first { it.id == "ru" }.items.any { it.text == " " })
        assertTrue(cats.first { it.id == "kao" }.wide)
        assertTrue(cats.first { it.id == "en" }.items.any { it.text == "\\" })
        val (zh, lat) = repo.symbolCommons()
        assertEquals("，", zh[0].text)
        assertEquals(",", lat[0].text)
    }

    @Test fun calculator() {
        assertEquals("42", Calc.format(Calc.eval("12×3.5")!!))
        assertEquals("7", Calc.format(Calc.eval("1+2×3")!!))
        assertEquals("1024", Calc.format(Calc.eval("2¹⁰")!!))
        assertEquals("0.001", Calc.format(Calc.eval("10⁻³")!!))
        assertEquals("3.5", Calc.format(Calc.eval("7÷2")!!))
        assertEquals("3", Calc.format(Calc.eval("√9")!!))
        assertEquals("120", Calc.format(Calc.eval("5!")!!))
        assertEquals("2.5", Calc.format(Calc.eval("2,5×1")!!))
        assertEquals("6.283185307", Calc.format(Calc.eval("2×π")!!))
        assertNull(Calc.eval("42"))
        assertNull(Calc.eval("x+1"))
    }
}
