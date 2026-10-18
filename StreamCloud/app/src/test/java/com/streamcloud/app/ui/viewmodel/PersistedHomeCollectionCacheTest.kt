package com.streamcloud.app.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedHomeCollectionCacheTest {
    @Test
    fun emptyTimestampIsNotFresh() {
        assertFalse(
            isPersistedHomeCollectionCacheFresh(
                savedAtMillis = 0L,
                nowMillis = 10_000L,
                staleAfterMillis = 900_000L,
            ),
        )
    }

    @Test
    fun cacheWithinTtlIsFresh() {
        assertTrue(
            isPersistedHomeCollectionCacheFresh(
                savedAtMillis = 100_000L,
                nowMillis = 200_000L,
                staleAfterMillis = 900_000L,
            ),
        )
    }

    @Test
    fun cacheAtTtlBoundaryIsStale() {
        assertFalse(
            isPersistedHomeCollectionCacheFresh(
                savedAtMillis = 100_000L,
                nowMillis = 1_000_000L,
                staleAfterMillis = 900_000L,
            ),
        )
    }

    @Test
    fun futureTimestampIsNotFresh() {
        assertFalse(
            isPersistedHomeCollectionCacheFresh(
                savedAtMillis = 200_000L,
                nowMillis = 100_000L,
                staleAfterMillis = 900_000L,
            ),
        )
    }
}
