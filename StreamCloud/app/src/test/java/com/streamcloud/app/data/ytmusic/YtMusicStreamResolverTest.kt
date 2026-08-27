package com.streamcloud.app.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class YtMusicStreamResolverTest {

    @Test
    fun keepsOnlyDistinctNonBlankIdsWithinThePrefetchBudget() {
        assertEquals(
            listOf("first-video", "second-video", "third-video"),
            boundedPrefetchVideoIds(
                videoIds = listOf(" first-video ", "", "first-video", "second-video", "third-video"),
                limit = 3,
            ),
        )
    }

    @Test
    fun returnsNoIdsWhenThePrefetchBudgetIsZero() {
        assertEquals(
            emptyList<String>(),
            boundedPrefetchVideoIds(listOf("first-video"), limit = 0),
        )
    }

    @Test
    fun queuePrefetchStartsAtTheActiveTrackAndUsesTheLookAheadWindow() {
        assertEquals(
            listOf("playing", "next", "after-next"),
            queuePrefetchVideoIds(
                videoIds = listOf("previous", "playing", "next", "after-next", "later"),
                currentIndex = 1,
                lookAhead = 3,
            ),
        )
    }

    @Test
    fun queuePrefetchClampsAnOutOfRangeIndexAndDeduplicatesTheWindow() {
        assertEquals(
            listOf("last"),
            queuePrefetchVideoIds(
                videoIds = listOf("first", "last", "last"),
                currentIndex = 99,
                lookAhead = 4,
            ),
        )
    }

    @Test
    fun cachedStreamRetainsItsPlaybackContract() {
        val entry = StreamUrlCache.Entry(
            url = "https://example.test/audio",
            userAgent = "resolver-agent",
            expiryMs = 123_456L,
            clientLabel = "VISIONOS",
            requiresWebSessionHeaders = false,
            sessionFingerprint = null,
            contentLength = 9_876_543L,
            requiresByteRange = true,
            generation = 7L,
        )

        assertEquals(9_876_543L, entry.contentLength ?: -1L)
        assertEquals(true, entry.requiresByteRange)
        assertEquals(7L, entry.generation)
    }

    @Test
    fun staleResolverGenerationsAreNotReturnedAfterRecovery() {
        assertEquals(true, isResolutionGenerationCurrent(4L, 4L))
        assertEquals(false, isResolutionGenerationCurrent(3L, 4L))
    }

    @Test
    fun playbackLookAheadContainsOnlyCurrentAndImmediateNextTrack() {
        assertEquals(
            listOf("current", "next"),
            queuePrefetchVideoIds(
                videoIds = listOf("current", "next", "later"),
                currentIndex = 0,
                lookAhead = YtMusicStreamResolver.PLAYBACK_LOOKAHEAD_COUNT,
            ),
        )
    }
}