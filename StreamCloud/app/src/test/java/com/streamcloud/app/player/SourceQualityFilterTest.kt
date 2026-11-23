package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceQualityFilterTest {
    private val sources = listOf(
        PlayerSource("uhd", "https://example.com/uhd", "UHD WEB-DL", "Provider"),
        PlayerSource("1080", "https://example.com/1080", "Movie 1080p", "Provider"),
        PlayerSource("720", "https://example.com/720", "HD", "Provider"),
        PlayerSource("tag-wins", "https://example.com/tag-wins", "Movie 4K", "Provider", qualityTag = "720p"),
        PlayerSource("unknown", "https://example.com/unknown", "Fast stream", "Provider"),
    )

    @Test
    fun disabledFilterKeepsEverySource() {
        assertEquals(sources, SourceQualityFilter.filter(sources, false, setOf("4K")))
    }

    @Test
    fun enabledFilterKeepsOnlySelectedQualityBuckets() {
        val filtered = SourceQualityFilter.filter(sources, true, setOf("4K", "1080p"))
        assertEquals(listOf("uhd", "1080"), filtered.map { it.id })
    }

    @Test
    fun unknownQualityCanBeSelectedExplicitly() {
        val filtered = SourceQualityFilter.filter(sources, true, setOf("Unknown"))
        assertEquals(listOf("unknown"), filtered.map { it.id })
    }

    @Test
    fun explicitQualityMetadataTakesPrecedenceOverTitles() {
        assertEquals("720p", SourceQualityFilter.bucket(sources[3]))
    }
}
