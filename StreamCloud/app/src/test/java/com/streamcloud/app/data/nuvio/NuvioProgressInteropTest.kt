package com.streamcloud.app.data.nuvio

import org.junit.Assert.*
import org.junit.Test

open class NuvioProgressInteropTest {
    private val timestamp = 1_700_000_000_000L
    private val episode = NuvioWatchProgressDeleteKey(123L, "tv", 2, 5)

    @Test fun officialMillisecondsIncludeEarlyPlaybackAndShortVideos() {
        for ((position, duration) in listOf(1_000L to 2_400_000L, 10_000L to 30_000L, 24_000L to 2_400_000L)) {
            assertEquals(
                NuvioProgressTimes(position, duration, timestamp),
                normalizeNuvioProgressTimes(position, duration, timestamp),
            )
        }
    }

    @Test fun missingTimestampDoesNotImplySeconds() {
        assertEquals(NuvioProgressTimes(1_000L, 30_000L, null), normalizeNuvioProgressTimes(1_000L, 30_000L, 0L))
    }

    @Test fun explicitLegacySecondsRemainReadable() {
        assertEquals(
            NuvioProgressTimes(2_016_000L, 2_400_000L, timestamp),
            normalizeNuvioProgressTimes(2_016L, 2_400_000L, timestamp / 1_000L),
        )
    }

    @Test fun newEpisodesUseNuvioStorageKeysAndSeparatePlayableVideoIds() {
        val target = nuvioProgressUploadTargets(episode, emptyList()).single()
        assertEquals("tmdb:123_s2e5", target.remoteProgressKey)
        assertEquals("tmdb:123:2:5", target.remoteVideoId)
        assertEquals(NuvioEpisodeNumbers(2, 5), nuvioEpisodeNumbers(null, null, null, null, target.remoteProgressKey))
    }

    @Test fun existingImdbAndLegacyAliasesAreUpdatedTogether() {
        val imdb = episode.copy(
            remoteContentId = "tt1234567", remoteVideoId = "tt1234567:2:5",
            remoteProgressKey = "tt1234567_s2e5",
        )
        val legacy = episode.copy(remoteProgressKey = "tmdb:123:2:5")
        assertEquals(listOf(imdb, legacy), nuvioProgressUploadTargets(episode, listOf(imdb, legacy, imdb)))
    }

    @Test fun phoneAcceptsTv84PercentWithoutEchoingItsOldOnePercent() {
        assertTrue(shouldUseNuvioProgress(
            timestamp, 2_400_000L, timestamp + 60_000L, 2_400_000L, 2_400_000L,
            existingPositionMs = 24_000L, incomingPositionMs = 2_016_000L,
        ))
        val cloud = nuvioProgressFingerprint(2_016_000L, 2_400_000L, timestamp + 60_000L)
        assertFalse(isPendingNuvioProgress(cloud, cloud))
        assertEquals(84.0, 2_016_000.0 / 2_400_000.0 * 100, 0.001)
    }

    @Test fun failedPushAndPlaybackDuringPushRemainPending() {
        val oldAck = nuvioProgressFingerprint(24_000L, 2_400_000L, timestamp)
        val sent = nuvioProgressFingerprint(2_016_000L, 2_400_000L, timestamp + 60_000L)
        assertTrue(isPendingNuvioProgress(sent, oldAck))
        assertFalse(isPendingNuvioProgress(sent, sent))
        val later = nuvioProgressFingerprint(2_026_000L, 2_400_000L, timestamp + 70_000L)
        assertTrue(isPendingNuvioProgress(later, sent))
    }

    @Test fun pendingLocalEditsSurvivePullEvenWhenAnotherDeviceClockIsAhead() {
        assertFalse(shouldUseNuvioProgress(
            timestamp, 2_400_000L, timestamp + 60_000L, 2_400_000L, 2_400_000L,
            existingPositionMs = 2_016_000L, incomingPositionMs = 24_000L,
            hasPendingLocalEdit = true,
        ))
    }

    @Test fun equalTimestampEpisodeAliasesResolveDeterministically() {
        assertTrue(shouldUseNuvioProgress(
            timestamp, 2_400_000L, timestamp, 2_400_000L, 2_400_000L,
            existingPositionMs = 24_000L, incomingPositionMs = 2_016_000L,
        ))
        assertFalse(shouldUseNuvioProgress(
            timestamp, 2_400_000L, timestamp, 2_400_000L, 2_400_000L,
            existingPositionMs = 2_016_000L, incomingPositionMs = 24_000L,
        ))
    }

    @Test fun remoteSnapshotDeletionDoesNotRemovePendingPlaybackWithAnOlderDeviceClock() {
        assertFalse(shouldRemoveMissingNuvioSnapshotRow(true, 100L, 200L, hasPendingLocalEdit = true))
        assertTrue(shouldRemoveMissingNuvioSnapshotRow(true, 100L, 200L))
    }
}
