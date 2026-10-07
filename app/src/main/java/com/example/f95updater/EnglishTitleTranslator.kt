package com.example.f95updater

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

object EnglishTitleTranslator {
    private const val NO_TRANSLATION = "\u0000"
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun translateIfNeeded(text: String): Result<String?> = runCatching {
        val q = text.trim()
        if (q.length < 2) return@runCatching null
        cache[q]?.let { cached -> return@runCatching cached.takeIf { it != NO_TRANSLATION } }

        val language = LanguageIdentification.getClient()
            .identifyLanguage(q)
            .await()

        if (language == "und" || language == TranslateLanguage.ENGLISH) {
            cache[q] = NO_TRANSLATION
            return@runCatching null
        }

        val source = TranslateLanguage.fromLanguageTag(language)
        if (source.isNullOrBlank() || source == TranslateLanguage.ENGLISH) {
            cache[q] = NO_TRANSLATION
            return@runCatching null
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
        try {
            translator.downloadModelIfNeeded().await()
            val translated = translator.translate(q).await().trim()
            val useful = translated.takeIf {
                it.isNotBlank() && !it.equals(q, ignoreCase = true)
            }
            cache[q] = useful ?: NO_TRANSLATION
            useful
        } finally {
            translator.close()
        }
    }
}
