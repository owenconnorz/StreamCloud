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
}