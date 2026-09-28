package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCompletionPolicyTest {

    @Test
    fun playbackMustEndOrReachNinetyFivePercent() {
        assertFalse(playbackReachedWatchCompletion(94_999L, 100_000L, playbackEnded = false))
        assertTrue(playbackReachedWatchCompletion(95_000L, 100_000L, playbackEnded = false))
        assertTrue(playbackReachedWatchCompletion(0L, 0L, playbackEnded = true))
    }

    @Test
    fun validTvIdentityCreatesEpisodeTarget() {
        val target = completedWatchTarget(
            WatchProgressKey(
                tmdbId = 42L,
                title = "The Show S2E3 · Episode title",
                posterUrl = "poster",
                mediaType = "tv",
                seasonNumber = 2,
                episodeNumber = 3,
                showTitle = "The Show",
                episodeTitle = "Episode title",
            ),
        )

        assertEquals(
            CompletedWatchTarget.Episode(
                tmdbId = 42L,
                seasonNumber = 2,
                episodeNumber = 3,
                showTitle = "The Show",
                episodeTitle = "Episode title",
            ),
            target,
        )
    }

    @Test
    fun tvIdentityCanUsePlayerSeasonAndEpisodeFallbacks() {
        val target = completedWatchTarget(
            key = WatchProgressKey(
                tmdbId = 42L,
                title = "The Show S1E4",
                posterUrl = null,
                mediaType = "tv",
            ),
            fallbackSeasonNumber = 1,
            fallbackEpisodeNumber = 4,
        )

        assertEquals(
            CompletedWatchTarget.Episode(
                tmdbId = 42L,
                seasonNumber = 1,
                episodeNumber = 4,
                showTitle = "The Show",
                episodeTitle = null,
            ),
            target,
        )
    }

    @Test
    fun invalidTvIdentityIsNotMistakenForAMovie() {
        val incomplete = completedWatchTarget(
            WatchProgressKey(
                tmdbId = 42L,
                title = "The Show",
                posterUrl = null,
                mediaType = "tv",
            ),
        )
        val synthetic = completedWatchTarget(
            WatchProgressKey(
                tmdbId = -42L,
                title = "The Show S1E1",
                posterUrl = null,
                mediaType = "tv",
                seasonNumber = 1,
                episodeNumber = 1,
            ),
        )

        assertNull(incomplete)
        assertNull(synthetic)
    }

    @Test
    fun validMovieIdentityCreatesMovieTarget() {
        val target = completedWatchTarget(
            WatchProgressKey(
                tmdbId = 42L,
                title = "A Movie",
                posterUrl = "poster",
                mediaType = "movie",
                seasonNumber = 1,
                episodeNumber = 1,
            ),
        )

        assertEquals(
            CompletedWatchTarget.Movie(
                tmdbId = 42L,
                title = "A Movie",
                posterUrl = "poster",
            ),
            target,
        )
    }

    @Test
    fun seasonZeroSpecialsAreValidEpisodes() {
        val target = completedWatchTarget(
            WatchProgressKey(
                tmdbId = 42L,
                title = "The Show S0E1",
                posterUrl = null,
                mediaType = "tv",
                seasonNumber = 0,
                episodeNumber = 1,
            ),
        )

        assertTrue(target is CompletedWatchTarget.Episode)
    }
}