package com.kk.shurufa

import com.kk.shurufa.ime.ClipHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the clipboard's filters recognize, and the part of a clip each one pastes. */
class ClipKindTest {
    private fun kind(s: String) = ClipHistory.classify(s)?.let { it.name to it.value }

    @Test fun codes() {
        assertEquals("验证码" to "482913", kind("【某某银行】您的验证码是482913，5分钟内有效，请勿泄露。"))
        assertEquals("验证码" to "7731", kind("7731"))
        assertEquals("验证码" to "553201", kind("Your verification code is 553201"))
        // a year in a sentence is not a code
        assertNull(kind("我们 2024 年见过一次"))
    }

    @Test fun links() {
        assertEquals("链接" to "https://github.com/kaichengkou/KK_ShuRuFa", kind("https://github.com/kaichengkou/KK_ShuRuFa"))
        assertEquals("链接" to "https://b23.tv/AbCd12", kind("【好看的视频】 https://b23.tv/AbCd12 复制打开"))
        assertEquals("链接" to "www.example.com/a", kind("看这里：www.example.com/a。"))
    }

    @Test fun numbersAndMail() {
        assertEquals("号码" to "13812345678", kind("联系人张三 138-1234-5678"))
        assertEquals("号码" to "+86 10 6552 9988", kind("+86 10 6552 9988"))
        assertEquals("邮箱" to "someone@example.com", kind("发到 someone@example.com 就行"))
    }

    @Test fun formulas() {
        assertEquals("公式", kind("\\frac{a+b}{2} \\geq \\sqrt{ab}")?.first)
        assertEquals("公式", kind("x^2 + 2x + 1 = 0")?.first)
        assertEquals("公式", kind("∫ sin x dx = −cos x + C")?.first)
        assertNull(kind("今天下午三点开会"))
    }
}
