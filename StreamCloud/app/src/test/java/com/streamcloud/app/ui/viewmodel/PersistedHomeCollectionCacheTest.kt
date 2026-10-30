package com.streamcloud.app.ui.viewmodel

import org.junit.Assert.assertEquals
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

    @Test
    fun refreshOnlyTargetsMissingOrExpiredShelves() {
        val staleIds = staleHomeCollectionIds(
            collectionIds = listOf("fresh", "expired", "missing"),
            cachedCollectionIds = setOf("fresh", "expired"),
            savedAtByCollectionId = mapOf(
                "fresh" to 95_000L,
                "expired" to 10_000L,
            ),
            legacySavedAtMillis = 0L,
            nowMillis = 100_000L,
            staleAfterMillis = 50_000L,
        )

        assertEquals(setOf("expired", "missing"), staleIds)
    }

    @Test
    fun oldWholeHomeTimestampRemainsAValidFallbackForExistingCaches() {
        val staleIds = staleHomeCollectionIds(
            collectionIds = listOf("one", "two"),
            cachedCollectionIds = setOf("one", "two"),
            savedAtByCollectionId = emptyMap(),
            legacySavedAtMillis = 95_000L,
            nowMillis = 100_000L,
            staleAfterMillis = 50_000L,
        )

        assertTrue(staleIds.isEmpty())
    }

    @Test
    fun visibleCachedRowsReleaseTheHomeLoadingGateDuringRefresh() {
        assertFalse(shouldShowMoviesHomeLoading(hasVisibleRows = true))
    }

    @Test
    fun emptyHomeKeepsLoadingUntilAVisibleRowArrives() {
        assertTrue(shouldShowMoviesHomeLoading(hasVisibleRows = false))
    }
}
