package com.kk.shurufa

import com.kk.shurufa.ime.ZhNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 大写金额. */
class ZhNumberTest {
    @Test fun writesAmounts() {
        assertEquals("壹佰贰拾叁元整", ZhNumber.money("123"))
        assertEquals("壹仟零伍拾元整", ZhNumber.money("1050"))
        assertEquals("壹仟贰佰叁拾肆元伍角整", ZhNumber.money("1234.5"))
        assertEquals("壹拾万零叁元整", ZhNumber.money("100003"))
        assertEquals("壹亿元整", ZhNumber.money("100000000"))
        assertEquals("壹万零壹元整", ZhNumber.money("10001"))
        assertEquals("伍角整", ZhNumber.money("0.5"))
        assertEquals("叁元零伍分", ZhNumber.money("3.05"))
        assertEquals("零元整", ZhNumber.money("0"))
        assertNull(ZhNumber.money("abc"))
    }
}
