package com.kk.shurufa.ime

import android.content.Context
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.PopupOverlay

/** What fills the area under the candidate bar. */
enum class Panel { LETTERS, NUM, SYMBOLS, MATH, PHONE, CURSOR, CLIPBOARD, CANDIDATES, QUICK, EMOJI, VOICE, AI }

/**
 * 输入到查询: the keys type into a query of the keyboard's own instead of the app, and the bar
 * shows what the query finds. One mechanism for three tools.
 */
enum class Sink {
    /** 表情查找: emoji whose names match. */
    EMOJI,
    /** 翻译: the translation strip above the bar. */
    TRANSLATE,
    /** AI 输入指令: an instruction for the AI panel. */
    AI,
}

/**
 * What a feature of the keyboard (emoji, clipboard, translation, AI, voice) may ask of the input
 * method. Features own their state and views; the input method owns the window, the panels and
 * the bar.
 */
interface Host {
    val context: Context
    val prefs: Prefs
    val kbTheme: KbTheme
    val haptics: Haptics
    val editor: Editor
    val overlay: PopupOverlay
    val panel: Panel
    /** The query typed while a [Sink] is open. */
    val query: StringBuilder
    val sink: Sink?
    /** The keyboard's current language is Chinese (Rime). */
    val isZh: Boolean
    val language: String

    fun show(panel: Panel)
    fun syncBar()
    /** Commits whatever pinyin is being composed, as the first candidate. */
    fun finishComposing()
    /** Starts typing into a query ([initial] is its first text); [panel] goes to the letters. */
    fun openSink(sink: Sink, initial: String = "")
    /** Closes the query; with [to] set, that panel opens. */
    fun closeSink(to: Panel? = null)
    fun openSettings(page: String? = null)
    /** A key area wired to the input method, for a panel's bottom row. */
    fun newKeyboard(): KeyboardView
    /** A short notice over the bar, instead of a toast over the app. */
    fun notice(text: String)
}
