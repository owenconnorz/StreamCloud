package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MoviePlayerSessionMergeOrderTest {
    @Test
    fun mergingRefreshResultsDoesNotReorderProviderPriorityFallbacks() {
        val preferredProvider = PlayerSource(
            id = "preferred",
            url = "https://example.com/preferred",
            label = "Preferred",
            addonName = "Preferred provider",
            qualityTag = "480p",
        )
        val lowerProvider = PlayerSource(
            id = "lower",
            url = "https://example.com/lower",
            label = "Lower",
            addonName = "Lower provider",
            qualityTag = "4K",
        )
        val refreshed = PlayerSource(
            id = "refreshed",
            url = "https://example.com/refreshed",
            label = "Refreshed",
            addonName = "Refreshed provider",
            qualityTag = "1080p",
        )

        try {
            MoviePlayerSession.set(listOf(preferredProvider, lowerProvider))
            MoviePlayerSession.mergeSources(listOf(refreshed))

            assertEquals(
                listOf("preferred", "lower", "refreshed"),
                MoviePlayerSession.sources.map { it.id },
            )
        } finally {
            MoviePlayerSession.clear()
        }
    }
}