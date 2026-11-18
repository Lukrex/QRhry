package com.qrhry.app.data.online

import java.text.Normalizer
import java.util.Locale

object CatalogValidation {
    fun validationError(metadata: CatalogPublishMetadata): String? {
        val description = metadata.description.trim()
        val author = metadata.authorName.trim()
        val language = metadata.languageTag.trim()
        if (description.isEmpty() || description.length > 500 || description.any(Char::isISOControl)) return "description"
        if (author.length !in 2..40 || author.any(Char::isISOControl)) return "author"
        if (language.length !in 2..35 || !LANGUAGE_TAG.matches(language)) return "language"
        val locale = Locale.forLanguageTag(language)
        if (locale.language.isBlank() || locale.toLanguageTag() == "und") return "language"
        return null
    }

    fun normalizeSearchTerm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(NON_WORD, " ")
        .split(WHITESPACE)
        .filter(String::isNotBlank)
        .distinct()
        .take(10)
        .joinToString(" ")

    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
}
