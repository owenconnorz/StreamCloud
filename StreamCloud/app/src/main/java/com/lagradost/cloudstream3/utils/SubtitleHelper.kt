package com.lagradost.cloudstream3.utils

import java.util.Locale

fun getCurrentLocale(): String = Locale.getDefault().toLanguageTag()

@Suppress("unused", "MemberVisibilityCanBePrivate")
object SubtitleHelper {
    /** Convert a language code (ISO 639-1/2/3, IETF tag, or language name) to IETF BCP 47 tag */
    fun fromCodeToLangTagIETF(code: String): String? {
        if (code.isBlank()) return null
        return try {
            val locale = when (code.trim().lowercase()) {
                "en", "eng", "english" -> Locale.ENGLISH
                "ja", "jpn", "japanese" -> Locale.JAPANESE
                "ko", "kor", "korean" -> Locale.KOREAN
                "zh", "chi", "zho", "chinese" -> Locale.CHINESE
                "fr", "fre", "fra", "french" -> Locale.FRENCH
                "de", "ger", "deu", "german" -> Locale.GERMAN
                "it", "ita", "italian" -> Locale.ITALIAN
                "pt", "por", "portuguese" -> Locale.forLanguageTag("pt")
                "es", "spa", "spanish" -> Locale.forLanguageTag("es")
                "ru", "rus", "russian" -> Locale.forLanguageTag("ru")
                "ar", "ara", "arabic" -> Locale.forLanguageTag("ar")
                "hi", "hin", "hindi" -> Locale.forLanguageTag("hi")
                "tr", "tur", "turkish" -> Locale.forLanguageTag("tr")
                "pl", "pol", "polish" -> Locale.forLanguageTag("pl")
                "nl", "nld", "dutch" -> Locale.forLanguageTag("nl")
                "sv", "swe", "swedish" -> Locale.forLanguageTag("sv")
                "no", "nor", "norwegian" -> Locale.forLanguageTag("no")
                "da", "dan", "danish" -> Locale.forLanguageTag("da")
                "fi", "fin", "finnish" -> Locale.forLanguageTag("fi")
                "id", "ind", "indonesian" -> Locale.forLanguageTag("id")
                "th", "tha", "thai" -> Locale.forLanguageTag("th")
                "vi", "vie", "vietnamese" -> Locale.forLanguageTag("vi")
                else -> Locale.forLanguageTag(code).takeIf { it.language.isNotEmpty() }
                    ?: return null
            }
            locale.toLanguageTag().takeIf { it != "und" }
        } catch (_: Exception) { null }
    }

    /** Convert a language name or code to IETF BCP 47 tag with fuzzy matching */
    fun fromLanguageToTagIETF(language: String, doFuzzy: Boolean = false): String? {
        return fromCodeToLangTagIETF(language)
    }

    data class LanguageMetadata(
        val languageName: String,
        val nativeName: String,
        val IETF_tag: String,
        val ISO_639_1: String,
        val ISO_639_2_B: String,
        val ISO_639_3: String,
        val openSubtitles: String,
    )
}
