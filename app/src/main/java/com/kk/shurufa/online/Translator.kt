package com.kk.shurufa.online

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * 翻译 with ML Kit: models (about 30 MB per language) download once, then translation runs
 * on the phone without sending text anywhere. Callbacks arrive on the main thread.
 */
class Translator {
    private val cache = HashMap<Pair<String, String>, com.google.mlkit.nl.translate.Translator>()

    companion object {
        fun mlkit(id: String) = when (id) {
            "zh" -> TranslateLanguage.CHINESE; "ru" -> TranslateLanguage.RUSSIAN; "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN; "fr" -> TranslateLanguage.FRENCH; "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH; else -> TranslateLanguage.ENGLISH
        }
    }

    fun translate(text: String, from: String, to: String, wifiOnly: Boolean, done: (Result<String>) -> Unit) {
        if (from == to || text.isBlank()) { done(Result.success(text)); return }
        val t = cache.getOrPut(from to to) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(mlkit(from)).setTargetLanguage(mlkit(to)).build())
        }
        val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        t.downloadModelIfNeeded(cond)
            .addOnSuccessListener {
                t.translate(text).addOnSuccessListener { done(Result.success(it)) }.addOnFailureListener { done(Result.failure(it)) }
            }
            .addOnFailureListener { done(Result.failure(IllegalStateException(if (wifiOnly) "翻译模型还没下载（设置为仅 Wi-Fi 下载）" else "翻译模型下载失败，检查网络"))) }
    }

    fun close() { cache.values.forEach { it.close() }; cache.clear() }
}
