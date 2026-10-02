package com.streamcloud.app.player

import com.streamcloud.app.data.api.TmdbEpisode
import com.streamcloud.app.data.api.TmdbTvSeasonSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BingeEpisodeQueueTest {
    @Test
    fun selectsTheNextLoadedEpisodeInTheCurrentSeason() {
        val result = buildBingeEpisodeQueue(
            tmdbId = 42L,
            showTitle = "Sample Show",
            fallbackPosterUrl = null,
            currentSeason = 1,
            currentEpisode = 2,
            seasonSummaries = listOf(TmdbTvSeasonSummary(seasonNumber = 1, episodeCount = 3)),
            episodeDetails = listOf(
                TmdbEpisode(seasonNumber = 1, episodeNumber = 1, name = "First"),
                TmdbEpisode(seasonNumber = 1, episodeNumber = 2, name = "Second"),
                TmdbEpisode(seasonNumber = 1, episodeNumber = 3, name = "Third"),
            ),
        )

        assertEquals(1, result.currentIndex)
        assertEquals(3, result.episodes[result.currentIndex + 1].episodeNumber)
        assertEquals("Third", result.episodes[result.currentIndex + 1].episodeTitle)
    }

    @Test
    fun selectsTheFirstEpisodeOfTheNextSeasonAtASeasonBoundary() {
        val result = buildBingeEpisodeQueue(
            tmdbId = 42L,
            showTitle = "Sample Show",
            fallbackPosterUrl = null,
            currentSeason = 1,
            currentEpisode = 2,
            seasonSummaries = listOf(
                TmdbTvSeasonSummary(seasonNumber = 1, episodeCount = 2),
                TmdbTvSeasonSummary(seasonNumber = 2, episodeCount = 8),
            ),
            episodeDetails = listOf(
                TmdbEpisode(seasonNumber = 1, episodeNumber = 1),
                TmdbEpisode(seasonNumber = 1, episodeNumber = 2),
            ),
        )

        val next = result.episodes[result.currentIndex + 1]
        assertEquals(2, next.seasonNumber)
        assertEquals(1, next.episodeNumber)
    }

    @Test
    fun createsCurrentAndNextEpisodeEntriesBeforeSeasonDetailsLoad() {
        val result = buildBingeEpisodeQueue(
            tmdbId = 42L,
            showTitle = "Sample Show",
            fallbackPosterUrl = null,
            currentSeason = 3,
            currentEpisode = 4,
            seasonSummaries = listOf(TmdbTvSeasonSummary(seasonNumber = 3, episodeCount = 10)),
            episodeDetails = emptyList(),
        )

        assertEquals(0, result.currentIndex)
        assertEquals(listOf(4, 5), result.episodes.map { it.episodeNumber })
    }

    @Test
    fun doesNotInventANextEpisodeAfterTheSeriesFinale() {
        val result = buildBingeEpisodeQueue(
            tmdbId = 42L,
            showTitle = "Sample Show",
            fallbackPosterUrl = null,
            currentSeason = 2,
            currentEpisode = 8,
            seasonSummaries = listOf(TmdbTvSeasonSummary(seasonNumber = 2, episodeCount = 8)),
            episodeDetails = emptyList(),
        )

        assertEquals(0, result.currentIndex)
        assertEquals(1, result.episodes.size)
        assertTrue(result.episodes.single().progressKey?.mediaType == "tv")
    }
}