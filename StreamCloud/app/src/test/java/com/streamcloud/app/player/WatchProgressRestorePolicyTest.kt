package com.streamcloud.app.player

import com.streamcloud.app.data.library.WatchProgressEntity
import com.streamcloud.app.ui.viewmodel.latestContinueWatchingByTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressRestorePolicyTest {
    private val episodeKey = WatchProgressKey(
        tmdbId = 42L,
        title = "Example Show S2E3 · The Example",
        posterUrl = null,
        mediaType = "tv",
    )

    @Test
    fun restoreRequiresMatchingMediaIdentity() {
        assertEquals(40_000L, restorableWatchPosition(episodeKey, progress()))
        assertNull(restorableWatchPosition(episodeKey, progress(tmdbId = 43L)))
        assertNull(restorableWatchPosition(episodeKey, progress(title = "Example Show S2E4")))
        assertNull(restorableWatchPosition(episodeKey, progress(mediaType = "movie")))
        assertNull(restorableWatchPosition(episodeKey, null))
    }

    @Test
    fun explicitEpisodeIdentityAllowsDisplayTitleChanges() {
        val explicitKey = episodeKey.copy(seasonNumber = 2, episodeNumber = 3)
        assertEquals(
            40_000L,
            restorableWatchPosition(
                explicitKey,
                progress(title = "Example Show — new episode title", seasonNumber = 2, episodeNumber = 3),
            ),
        )
        assertNull(
            restorableWatchPosition(
                explicitKey,
                progress(title = "Example Show — new episode title", seasonNumber = 2, episodeNumber = 4),
            ),
        )
    }

    @Test
    fun restoreRejectsNearCompleteAndInvalidPositions() {
        assertNull(restorableWatchPosition(episodeKey, progress(positionMs = 95_000L)))
        assertNull(restorableWatchPosition(episodeKey, progress(positionMs = 100_000L)))
        assertNull(restorableWatchPosition(episodeKey, progress(positionMs = 5_000L)))
        assertEquals(40_000L, restorableWatchPosition(episodeKey, progress(durationMs = 0L, positionMs = 40_000L)))
        assertEquals(94_999L, restorableWatchPosition(
            episodeKey,
            progress(positionMs = 94_999L),
        ))
    }

    @Test
    fun unknownDurationStreamsStillPersistShowAndRestoreAValidPosition() {
        val key = episodeKey.copy(seasonNumber = 2, episodeNumber = 3)
        val saved = progress(durationMs = 0L, positionMs = 40_000L, seasonNumber = 2, episodeNumber = 3)

        assertTrue(shouldPersistWatchProgress(40_000L, 0L, playbackEnded = false, completionHandled = false))
        assertEquals(40_000L, restorableWatchPosition(key, saved))
        assertFalse(shouldPersistWatchProgress(5_000L, 0L, playbackEnded = false, completionHandled = false))
        assertFalse(shouldPersistWatchProgress(40_000L, 0L, playbackEnded = true, completionHandled = false))
        assertFalse(shouldPersistWatchProgress(95_000L, 100_000L, playbackEnded = false, completionHandled = false))
    }

    @Test
    fun currentDurationMustContainAValidResumePoint() {
        assertTrue(canRestoreWatchPosition(40_000L, 100_000L))
        assertFalse(canRestoreWatchPosition(40_000L, 30_000L))
        assertFalse(canRestoreWatchPosition(95_000L, 100_000L))
        assertFalse(canRestoreWatchPosition(40_000L, 0L))
    }

    @Test
    fun fallbackBingeKeysIncludeSeasonAndEpisode() {
        val seasonTwoEpisodeThree = BingeEpisode(
            tmdbId = 42L,
            title = "Example Show",
            seasonNumber = 2,
            episodeNumber = 3,
            episodeTitle = "The Example",
        )
        val seasonTwoEpisodeFour = seasonTwoEpisodeThree.copy(episodeNumber = 4)

        val firstKey = progressKeyForBingeEpisode(seasonTwoEpisodeThree)
        val nextKey = progressKeyForBingeEpisode(seasonTwoEpisodeFour)

        assertTrue(firstKey.title.contains("S2E3"))
        assertTrue(nextKey.title.contains("S2E4"))
        assertFalse(firstKey.title == nextKey.title)
        assertEquals("tv", firstKey.mediaType)
    }

    @Test
    fun fallbackPreservesAnExplicitProgressKey() {
        val explicitKey = episodeKey.copy(tmdbId = 42L)
        val episode = BingeEpisode(
            tmdbId = 42L,
            title = "Example Show",
            seasonNumber = 2,
            episodeNumber = 3,
            progressKey = explicitKey,
        )

        assertEquals(explicitKey, progressKeyForBingeEpisode(episode))
    }

    @Test
    fun continueWatchingUsesTheLatestEpisodeEvenWhenProgressIsBelowOnePercent() {
        val olderEpisode = progress(
            tmdbId = 700L,
            title = "The Rookie S02E05",
            positionMs = 60_000L,
            durationMs = 1_000_000L,
            seasonNumber = 2,
            episodeNumber = 5,
            updatedAt = 100L,
        )
        val currentEpisode = progress(
            tmdbId = 700L,
            title = "The Rookie S02E09",
            positionMs = 10_000L,
            durationMs = 2_400_000L,
            seasonNumber = 2,
            episodeNumber = 9,
            updatedAt = 200L,
        )
        val movie = progress(
            tmdbId = 808L,
            mediaType = "movie",
            positionMs = 120_000L,
            durationMs = 1_000_000L,
            updatedAt = 150L,
        )

        assertEquals(
            listOf(currentEpisode, movie),
            latestContinueWatchingByTitle(listOf(olderEpisode, currentEpisode, movie)),
        )
    }

    @Test
    fun continueWatchingBreaksEqualTimestampsBySeasonAndEpisode() {
        val episodeFive = progress(
            tmdbId = 700L,
            seasonNumber = 2,
            episodeNumber = 5,
            updatedAt = 200L,
        )
        val episodeNine = progress(
            tmdbId = 700L,
            seasonNumber = 2,
            episodeNumber = 9,
            updatedAt = 200L,
        )

        assertEquals(
            listOf(episodeNine),
            latestContinueWatchingByTitle(listOf(episodeFive, episodeNine)),
        )
    }

    private fun progress(
        tmdbId: Long = episodeKey.tmdbId,
        title: String = episodeKey.title,
        mediaType: String = episodeKey.mediaType,
        positionMs: Long = 40_000L,
        durationMs: Long = 100_000L,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0,
        updatedAt: Long = 0L,
    ) = WatchProgressEntity(
        tmdbId = tmdbId,
        title = title,
        posterUrl = null,
        mediaType = mediaType,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
}