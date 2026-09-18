package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.api.TmdbMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbSearchPaginationTest {
    @Test
    fun staleCompletionCannotOverwriteResetQuery() {
        val old = TmdbSearchPagination().reset("alien", generation = 1)
        val current = old.reset("arrival", generation = 2)

        val afterLateResponse = current.complete(
            query = "alien",
            generation = 1,
            responsePage = 3,
            responseTotalPages = 8,
        )

        assertEquals(current, afterLateResponse)
        assertEquals("arrival", afterLateResponse.query)
        assertEquals(0, afterLateResponse.page)
        assertTrue(afterLateResponse.isLoading)
    }

    @Test
    fun appendDeduplicatesIdsAndPreservesResultOrder() {
        val first = listOf(TmdbMovie(id = 1, title = "One"), TmdbMovie(id = 2, title = "Two"))
        val second = listOf(TmdbMovie(id = 2, title = "Duplicate"), TmdbMovie(id = 3, title = "Three"))

        val merged = mergeTmdbSearchResults(first, second)

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertEquals("Two", merged[1].displayTitle)
    }

    @Test
    fun providerReportedFinalPageEndsPaginationWithoutHardcodedCap() {
        val state = TmdbSearchPagination()
            .reset("long search", generation = 4)
            .complete("long search", generation = 4, responsePage = 137, responseTotalPages = 137)

        assertEquals(137, state.page)
        assertEquals(137, state.totalPages)
        assertTrue(state.endReached)
        assertFalse(state.canLoad)
    }

    @Test
    fun failureStopsAutomaticLoadAndExplicitBeginLoadMakesRetrySafe() {
        val failed = TmdbSearchPagination()
            .reset("retry", generation = 7)
            .fail("retry", generation = 7, message = "network unavailable")

        assertFalse(failed.isLoading)
        assertFalse(failed.canLoad)
        assertEquals("network unavailable", failed.error)

        val retrying = failed.beginLoad()
        assertTrue(retrying.isLoading)
        assertNull(retrying.error)
        assertFalse(retrying.canLoad)
    }

    @Test
    fun cacheReuseKeepsPageButAdoptsNewGenerationAndClearsTransientError() {
        val cached = TmdbSearchPagination(
            query = "cached",
            generation = 1,
            page = 2,
            totalPages = 5,
            error = "old failure",
        )

        val reused = cached.reuse(query = "cached", generation = 9)

        assertEquals(2, reused.page)
        assertEquals(5, reused.totalPages)
        assertEquals(9, reused.generation)
        assertNull(reused.error)
        assertFalse(reused.isLoading)
        assertTrue(reused.canLoad)
    }
}