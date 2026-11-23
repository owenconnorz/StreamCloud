package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanguageFilterTest {
    private val sources = listOf(
        PlayerSource("english", "https://example.com/en", "English WEB-DL", "Provider"),
        PlayerSource("japanese", "https://example.com/ja", "Japanese audio", "Provider"),
        PlayerSource("multi", "https://example.com/multi", "Dual Audio", "Provider"),
        PlayerSource("multiple-labels", "https://example.com/multiple", "English Spanish", "Provider"),
        PlayerSource("unknown", "https://example.com/unknown", "WEB-DL", "Provider"),
        PlayerSource("tagged", "https://example.com/tagged", "WEB-DL", "Provider", languageTag = "fr"),
    )

    @Test
    fun disabledFilterKeepsEverySource() {
        assertEquals(sources, SourceLanguageFilter.filter(sources, false, setOf("Japanese")))
    }

    @Test
    fun sourceFilterKeepsSelectedLanguageAndExplicitUnknownResults() {
        val filtered = SourceLanguageFilter.filter(sources, true, setOf("English", "Unknown"))
        assertEquals(listOf("english", "unknown"), filtered.map { it.id })
    }

    @Test
    fun languageMetadataOverridesTheDisplayLabel() {
        assertEquals("French", SourceLanguageFilter.bucket(sources.last()))
    }

    @Test
    fun multipleNamedLanguagesAreClassifiedAsMulti() {
        assertEquals("Multi", SourceLanguageFilter.bucket(sources[3]))
    }

    @Test
    fun trackMatcherRecognizesLanguageCodesAndMultilingualTracks() {
        assertTrue(SourceLanguageFilter.matchesTrack(setOf("English"), "eng", "Track 1"))
        assertTrue(SourceLanguageFilter.matchesTrack(setOf("Multi"), null, "Dual Audio"))
        assertFalse(SourceLanguageFilter.matchesTrack(setOf("Japanese"), "eng", "English"))
    }
}
