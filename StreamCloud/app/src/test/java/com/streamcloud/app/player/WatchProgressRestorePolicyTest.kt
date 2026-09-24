package com.streamcloud.app.player

import com.streamcloud.app.data.library.WatchProgressEntity
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
    fun restoreRejectsNearCompleteAndInvalidPositions() {
        assertNull(restorableWatchPosition(episodeKey, progress(positionMs = 95_000L)))
        assertNull(restorableWatchPosition(episodeKey, progress(positionMs = 100_000L)))
        assertNull(restorableWatchPosition(episodeKey, progress(positionMs = 5_000L)))
        assertNull(restorableWatchPosition(episodeKey, progress(durationMs = 0L)))
        assertEquals(94_999L, restorableWatchPosition(
            episodeKey,
            progress(positionMs = 94_999L),
        ))
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

    private fun progress(
        tmdbId: Long = episodeKey.tmdbId,
        title: String = episodeKey.title,
        mediaType: String = episodeKey.mediaType,
        positionMs: Long = 40_000L,
        durationMs: Long = 100_000L,
    ) = WatchProgressEntity(
        tmdbId = tmdbId,
        title = title,
        posterUrl = null,
        mediaType = mediaType,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = 0L,
    )
}