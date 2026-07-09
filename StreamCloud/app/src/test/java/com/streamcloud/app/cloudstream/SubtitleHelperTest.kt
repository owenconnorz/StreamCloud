package com.streamcloud.app.cloudstream

import com.lagradost.cloudstream3.utils.SubtitleHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the expanded SubtitleHelper language database.
 *
 * Validates that the 170-language database mirrors the canonical recloudstream/cloudstream
 * SubtitleHelper, covering all four lookup paths: IETF tag, ISO 639-1, ISO 639-2/B, and
 * ISO 639-3, as well as the deprecated plugin-compat methods.
 */
class SubtitleHelperTest {

    // ── fromCodeToLangTagIETF ────────────────────────────────────────────────

    @Test
    fun iso639_1_EnglishCodeReturnsIETFTag() {
        assertEquals("en", SubtitleHelper.fromCodeToLangTagIETF("en"))
    }

    @Test
    fun iso639_1_JapaneseCodeReturnsIETFTag() {
        assertEquals("ja", SubtitleHelper.fromCodeToLangTagIETF("ja"))
    }

    @Test
    fun iso639_3_FrenchCodeReturnsIETFTag() {
        // "fra" is ISO 639-3 for French
        assertEquals("fr", SubtitleHelper.fromCodeToLangTagIETF("fra"))
    }

    @Test
    fun iso639_2B_GermanCodeReturnsIETFTag() {
        // "deu" is ISO 639-2/B for German
        assertEquals("de", SubtitleHelper.fromCodeToLangTagIETF("deu"))
    }

    @Test
    fun openSubtitlesTagForSpanishReturnsIETFTag() {
        assertEquals("es", SubtitleHelper.fromCodeToLangTagIETF("es"))
    }

    @Test
    fun deprecatedHebrew_iw_CodeIsResolved() {
        // "iw" is the deprecated ISO 639-1 code for Hebrew still used by some plugins
        assertEquals("he", SubtitleHelper.fromCodeToLangTagIETF("iw"))
    }

    @Test
    fun deprecatedIndonesian_in_CodeIsResolved() {
        // "in" is the deprecated code for Indonesian
        assertEquals("id", SubtitleHelper.fromCodeToLangTagIETF("in"))
    }

    @Test
    fun nullCodeReturnsNull() {
        assertNull(SubtitleHelper.fromCodeToLangTagIETF(null))
    }

    @Test
    fun blankCodeReturnsNull() {
        assertNull(SubtitleHelper.fromCodeToLangTagIETF(""))
    }

    @Test
    fun koreanCodeReturnsIETFTag() {
        assertEquals("ko", SubtitleHelper.fromCodeToLangTagIETF("ko"))
    }

    @Test
    fun chineseSimplifiedIETFTagRoundtrips() {
        assertEquals("zh-hans", SubtitleHelper.fromCodeToLangTagIETF("zh-hans"))
    }

    @Test
    fun portugueseBrazilRoundtrips() {
        assertEquals("pt-br", SubtitleHelper.fromCodeToLangTagIETF("pt-br"))
    }

    // ── fromLanguageToTagIETF ────────────────────────────────────────────────

    @Test
    fun languageNameEnglishReturnsIETFTag() {
        assertEquals("en", SubtitleHelper.fromLanguageToTagIETF("English"))
    }

    @Test
    fun languageNameFrenchReturnsIETFTag() {
        assertEquals("fr", SubtitleHelper.fromLanguageToTagIETF("French"))
    }

    @Test
    fun languageNameSpanishReturnsIETFTag() {
        assertEquals("es", SubtitleHelper.fromLanguageToTagIETF("Spanish"))
    }

    @Test
    fun languageNameArabicReturnsIETFTag() {
        assertEquals("ar", SubtitleHelper.fromLanguageToTagIETF("Arabic"))
    }

    @Test
    fun codePassedAsNameFallsBackToCodeLookup() {
        // fromLanguageToTagIETF should also accept codes as input
        assertNotNull(SubtitleHelper.fromLanguageToTagIETF("de"))
    }

    // ── fromTagToEnglishLanguageName ─────────────────────────────────────────

    @Test
    fun ietfTagToEnglishNameForEnglish() {
        assertEquals("English", SubtitleHelper.fromTagToEnglishLanguageName("en"))
    }

    @Test
    fun ietfTagToEnglishNameForJapanese() {
        assertEquals("Japanese", SubtitleHelper.fromTagToEnglishLanguageName("ja"))
    }

    @Test
    fun ietfTagToEnglishNameForKorean() {
        assertEquals("Korean", SubtitleHelper.fromTagToEnglishLanguageName("ko"))
    }

    @Test
    fun ietfTagToEnglishNameForRussian() {
        assertEquals("Russian", SubtitleHelper.fromTagToEnglishLanguageName("ru"))
    }

    @Test
    fun ietfTagToEnglishNameForTurkish() {
        assertEquals("Turkish", SubtitleHelper.fromTagToEnglishLanguageName("tr"))
    }

    @Test
    fun nullTagReturnsNullEnglishName() {
        assertNull(SubtitleHelper.fromTagToEnglishLanguageName(null))
    }

    // ── fromTwoLettersToLanguage (deprecated plugin-compat method) ───────────

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForEnglish() {
        assertEquals("English", SubtitleHelper.fromTwoLettersToLanguage("en"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForFrench() {
        assertEquals("French", SubtitleHelper.fromTwoLettersToLanguage("fr"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForGerman() {
        assertEquals("German", SubtitleHelper.fromTwoLettersToLanguage("de"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForChinese() {
        assertEquals("Chinese", SubtitleHelper.fromTwoLettersToLanguage("zh"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForArabic() {
        assertEquals("Arabic", SubtitleHelper.fromTwoLettersToLanguage("ar"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForHindi() {
        assertEquals("Hindi", SubtitleHelper.fromTwoLettersToLanguage("hi"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun twoLetterCodeToLanguageNameForPortuguese() {
        assertEquals("Portuguese", SubtitleHelper.fromTwoLettersToLanguage("pt"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun unknownTwoLetterCodeReturnsNull() {
        assertNull(SubtitleHelper.fromTwoLettersToLanguage("xx"))
    }

    // ── fromThreeLettersToLanguage (deprecated plugin-compat method) ─────────

    @Test
    @Suppress("DEPRECATION")
    fun threeLetterIso639_3CodeReturnsLanguageName() {
        assertEquals("Japanese", SubtitleHelper.fromThreeLettersToLanguage("jpn"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun threeLetterIso639_3EngCodeReturnsEnglish() {
        assertEquals("English", SubtitleHelper.fromThreeLettersToLanguage("eng"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun unknownThreeLetterCodeReturnsNull() {
        assertNull(SubtitleHelper.fromThreeLettersToLanguage("xxx"))
    }
}
