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
}