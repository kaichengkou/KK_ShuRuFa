package com.kk.shurufa.ime

import android.content.Context

/** 俄语联想's word tables, one per language that has them, each read on first use. */
internal class WordPredictors(private val context: Context) {
    private val byLang = HashMap<String, WordPredictor>()

    /** The table of [lang] (in [WordPredictor.LANGS]), loaded. */
    fun get(lang: String): WordPredictor = byLang.getOrPut(lang) { WordPredictor(context, lang) }.also { it.load() }

    /** What every table learned, written out. */
    fun saveAll() = byLang.values.forEach { it.save() }

    /** Lets go of the tables of languages other than [keep], what they learned saved; they load again on first use. */
    fun trim(keep: String) {
        byLang.keys.filter { it != keep }.forEach { l -> byLang.remove(l)?.save() }
    }
}
