package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class TranslationResult(
    val translatedLines: List<String>,
    val detectedLanguage: String
)

object LyricsTranslator {

    suspend fun translateLines(lines: List<LyricLine>): TranslationResult? {
        val sampleText = lines
            .map { it.text }
            .filter { it.isNotBlank() && it != "♪" }
            .take(5)
            .joinToString("\n")

        if (sampleText.isBlank()) return null

        val langCode = detectLanguage(sampleText) ?: return null
        if (langCode == "en" || langCode == "und") return null

        val mlLang = TranslateLanguage.fromLanguageTag(langCode) ?: return null

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlLang)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)

        try {
            val modelReady = suspendCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }
            if (!modelReady) return null

            val translated = lines.map { line ->
                val text = line.text.trim()
                if (text.isBlank() || text == "♪") {
                    ""
                } else {
                    suspendCoroutine { cont ->
                        translator.translate(text)
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { cont.resume(text) }
                    }
                }
            }

            return TranslationResult(translated, langCode)
        } finally {
            translator.close()
        }
    }

    private suspend fun detectLanguage(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try {
            suspendCoroutine { cont ->
                identifier.identifyLanguage(text)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } finally {
            identifier.close()
        }
    }
}
