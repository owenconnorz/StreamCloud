package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.library.WatchProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingSelectionTest {

    @Test
    fun latestSyncedEpisodeWinsEvenWhenItsProgressIsBelowOnePercent() {
        val olderEpisode = progress(
            tmdbId = 700,
            season = 2,
            episode = 5,
            positionMs = 60_000,
            durationMs = 1_000_000,
            updatedAt = 100,
        )
        val currentEpisode = progress(
            tmdbId = 700,
            season = 2,
            episode = 9,
            positionMs = 10_000,
            durationMs = 2_400_000,
            updatedAt = 200,
        )
        val movie = progress(
            tmdbId = 808,
            mediaType = "movie",
            positionMs = 120_000,
            durationMs = 1_000_000,
            updatedAt = 150,
        )

        assertEquals(
            listOf(currentEpisode, movie),
            latestContinueWatchingByTitle(listOf(olderEpisode, currentEpisode, movie)),
        )
    }

    @Test
    fun seasonAndEpisodeBreakTiesTheSameWayForHomeAndTitleDetails() {
        val episodeFive = progress(tmdbId = 700, season = 2, episode = 5, updatedAt = 200)
        val episodeNine = progress(tmdbId = 700, season = 2, episode = 9, updatedAt = 200)

        assertEquals(
            listOf(episodeNine),
            latestContinueWatchingByTitle(listOf(episodeFive, episodeNine)),
        )
    }

    private fun progress(
        tmdbId: Long,
        mediaType: String = "tv",
        season: Int = 0,
        episode: Int = 0,
        positionMs: Long = 10_000,
        durationMs: Long = 2_400_000,
        updatedAt: Long,
    ) = WatchProgressEntity(
        tmdbId = tmdbId,
        title = if (mediaType == "tv") "The Rookie" else "Movie",
        posterUrl = null,
        mediaType = mediaType,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        seasonNumber = season,
        episodeNumber = episode,
    )
}
