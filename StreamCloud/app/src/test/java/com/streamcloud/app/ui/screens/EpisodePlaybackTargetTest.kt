package com.streamcloud.app.ui.screens

import com.streamcloud.app.data.api.TmdbTvSeasonSummary
import com.streamcloud.app.data.library.WatchedEpisodeEntity
import com.streamcloud.app.data.library.WatchProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePlaybackTargetTest {
    private val seasons = listOf(
        TmdbTvSeasonSummary(seasonNumber = 1, episodeCount = 5),
        TmdbTvSeasonSummary(seasonNumber = 2, episodeCount = 8),
        TmdbTvSeasonSummary(seasonNumber = 3, episodeCount = 4),
    )

    @Test
    fun resumesTheSavedEpisodeAndPreservesItsEpisodeTitle() {
        val target = chooseEpisodePlaybackTarget(
            inProgressEpisode = progress(
                title = "The Rookie S2E7 · Safety",
                seasonNumber = 2,
                episodeNumber = 7,
            ),
            watchedEpisodes = listOf(watched(season = 2, episode = 6, watchedAt = 100L)),
            seasons = seasons,
            selectedSeason = 1,
            seasonSelectedByUser = false,
        )

        assertEquals(EpisodePlaybackTarget(2, 7, true, "Safety"), target)
        assertEquals("Continue S2E7", target?.buttonLabel)
    }

    @Test
    fun playsTheEpisodeAfterTheMostRecentlyWatchedEpisode() {
        val target = chooseEpisodePlaybackTarget(
            inProgressEpisode = null,
            watchedEpisodes = listOf(
                watched(season = 1, episode = 5, watchedAt = 50L),
                watched(season = 2, episode = 6, watchedAt = 100L),
            ),
            seasons = seasons,
            selectedSeason = 1,
            seasonSelectedByUser = false,
        )

        assertEquals(EpisodePlaybackTarget(2, 7, false), target)
        assertEquals("Play S2E7", target?.buttonLabel)
    }

    @Test
    fun selectingASeasonMakesTheButtonUseThatSeason() {
        val target = chooseEpisodePlaybackTarget(
            inProgressEpisode = null,
            watchedEpisodes = listOf(watched(season = 1, episode = 5, watchedAt = 100L)),
            seasons = seasons,
            selectedSeason = 2,
            seasonSelectedByUser = true,
        )

        assertEquals(EpisodePlaybackTarget(2, 1, false), target)
    }

    @Test
    fun movesToTheNextSeasonAfterTheSelectedSeasonIsComplete() {
        val target = chooseEpisodePlaybackTarget(
            inProgressEpisode = null,
            watchedEpisodes = (1..5).map { watched(season = 1, episode = it, watchedAt = it.toLong()) },
            seasons = seasons,
            selectedSeason = 1,
            seasonSelectedByUser = true,
        )

        assertEquals(EpisodePlaybackTarget(2, 1, false), target)
    }

    @Test
    fun defaultsToTheFirstEpisodeOfTheSelectedSeasonWhenThereIsNoHistory() {
        val target = chooseEpisodePlaybackTarget(
            inProgressEpisode = null,
            watchedEpisodes = emptyList(),
            seasons = seasons,
            selectedSeason = 2,
            seasonSelectedByUser = true,
        )

        assertEquals(EpisodePlaybackTarget(2, 1, false), target)
        assertFalse(target?.isContinue ?: true)
    }

    @Test
    fun doesNotResumeAnEpisodeAlreadyMarkedWatched() {
        val target = chooseEpisodePlaybackTarget(
            inProgressEpisode = progress(seasonNumber = 2, episodeNumber = 6),
            watchedEpisodes = listOf(watched(season = 2, episode = 6, watchedAt = 100L)),
            seasons = seasons,
            selectedSeason = 2,
            seasonSelectedByUser = true,
        )

        assertEquals(EpisodePlaybackTarget(2, 7, false), target)
        assertTrue(target?.buttonLabel == "Play S2E7")
    }

    private fun progress(
        title: String = "The Rookie S2E7 · Safety",
        seasonNumber: Int,
        episodeNumber: Int,
    ) = WatchProgressEntity(
        tmdbId = 123L,
        title = title,
        posterUrl = null,
        mediaType = "tv",
        positionMs = 120_000L,
        durationMs = 2_400_000L,
        updatedAt = 200L,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    private fun watched(season: Int, episode: Int, watchedAt: Long) = WatchedEpisodeEntity(
        tmdbShowId = 123L,
        seasonNumber = season,
        episodeNumber = episode,
        showTitle = "The Rookie",
        watchedAt = watchedAt,
    )
}