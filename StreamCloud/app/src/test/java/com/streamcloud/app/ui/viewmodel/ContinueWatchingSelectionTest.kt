package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.library.WatchProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingSelectionTest {
    @Test
    fun legacyEpisodeProgressGetsSeasonAndEpisodeFromTitle() {
        val target = continueWatchingResumeTarget(
            entry(
                title = "From S01E01 · Long Day's Journey Into Night",
                mediaType = "tv",
            ),
        )

        assertEquals("tv", target.mediaType)
        assertEquals(1, target.seasonNumber)
        assertEquals(1, target.episodeNumber)
    }

    @Test
    fun savedSourceRouteProvidesEpisodeIdentityWhenColumnsAreMissing() {
        val target = continueWatchingResumeTarget(
            entry(
                title = "From",
                mediaType = "tv",
                sourceRoute = "sources:tv|||2|||8",
            ),
        )

        assertEquals("tv", target.mediaType)
        assertEquals(2, target.seasonNumber)
        assertEquals(8, target.episodeNumber)
    }

    @Test
    fun explicitEpisodeColumnsTakePrecedenceOverLegacyRoute() {
        val target = continueWatchingResumeTarget(
            entry(
                title = "From S01E01",
                mediaType = "tv",
                sourceRoute = "sources:tv|||1|||1",
                seasonNumber = 3,
                episodeNumber = 4,
            ),
        )

        assertEquals(3, target.seasonNumber)
        assertEquals(4, target.episodeNumber)
    }

    @Test
    fun movieResumeHasNoEpisodeIdentity() {
        val target = continueWatchingResumeTarget(
            entry(title = "Meet the Parents", mediaType = "movie"),
        )

        assertEquals("movie", target.mediaType)
        assertNull(target.seasonNumber)
        assertNull(target.episodeNumber)
    }

    private fun entry(
        title: String,
        mediaType: String,
        sourceRoute: String? = null,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0,
    ) = WatchProgressEntity(
        tmdbId = 42L,
        title = title,
        posterUrl = null,
        mediaType = mediaType,
        positionMs = 30_000L,
        durationMs = 1_000_000L,
        updatedAt = 1L,
        sourceRoute = sourceRoute,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
}
