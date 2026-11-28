package com.kk.shurufa.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** What a text field is, read from what the app says about it: its kind, and the marks its number pad offers. */
internal object Fields {
    /** 运算符列 of the standard number pad: five on screen, the rest by scrolling. */
    val NUM_OPS = listOf("+", "−", "×", "÷", "=", "%", "(", ")", "/", ",", "±")
    /** 电话 fields: what goes in a phone number (and the * and # of a dial pad). */
    val PHONE_OPS = listOf("+", "-", " ", "*", "#", "(", ")", ",", ";")
    val DATE_OPS = listOf("-", "/", ":", " ", ".")

    private val PASSWORDS = setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    private val PASSWORD_WORDS = listOf("密码", "password", "passcode", "пароль", "口令")
    private val ACCOUNT_WORDS = listOf("邮箱", "电子邮件", "email", "e-mail", "账号", "帐号", "用户名", "username", "user name", "login", "登录名",
        "почта", "логин", "网址", "url", "域名")
    /** Apps whose text fields are account names and the like (Google's sign-in). */
    private val ACCOUNT_APPS = setOf("com.google.android.gms", "com.google.android.gsf.login")
    private val ADDRESSES = setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)

    /**
     * "password", "address" (a mail or web address, an account name) or null, by what the field
     * says it is, and, for fields that do not say (login pages often don't), by its hint
     * ("邮箱或手机号", "Email or phone", "Пароль") and by the app (Google's own account screens).
     */
    fun kind(info: EditorInfo): String? {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return null
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation in PASSWORDS) return "password"
        if (variation in ADDRESSES) return "address"
        if (variation != InputType.TYPE_TEXT_VARIATION_NORMAL && variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) return null
        val words = listOfNotNull(info.hintText, info.label).joinToString(" ").lowercase()
        if (PASSWORD_WORDS.any { it in words }) return "password"
        if (ACCOUNT_WORDS.any { it in words }) return "address"
        if (info.packageName in ACCOUNT_APPS) return "address"
        return null
    }

    /**
     * The marks of the number pad's column for the field: a phone number's, a date's, a number's;
     * null in a text field (the arithmetic ones).
     */
    fun numberOps(info: EditorInfo): List<String>? {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> PHONE_OPS
            InputType.TYPE_CLASS_DATETIME -> DATE_OPS
            InputType.TYPE_CLASS_NUMBER -> buildList {
                if (info.inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0) add("-")
                if (info.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) { add("."); add(",") }
                if (variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD) addAll(listOf("-", "/", ":", " ").filter { it !in this })
            }.ifEmpty { listOf(" ") }
            else -> null
        }
    }
}
