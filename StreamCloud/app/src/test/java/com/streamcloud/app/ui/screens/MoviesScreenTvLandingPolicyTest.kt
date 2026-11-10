package com.streamcloud.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoviesScreenTvLandingPolicyTest {
    @Test
    fun topTenIsTheFirstFocusableShelfWhenAvailable() {
        assertEquals(
            "top_ten",
            selectFirstTvHomeFocusRowKey(
                hasContinueWatching = true,
                hasGenres = true,
                hasTopTen = true,
            ),
        )
    }

    @Test
    fun landingChoosesFirstFocusableShelfAtOrAfterVisibleItem() {
        val rows = listOf(
            "home_genres" to 4,
            "col_popular" to 7,
        )

        assertEquals(
            "home_genres" to 4,
            selectTvHomeFastScrollLandingRow(
                visibleFocusRows = rows,
                leadingVisibleIndex = 3,
                direction = 1,
                reachedBottom = false,
            ),
        )
    }

    @Test
    fun downwardLandingAtTheEndChoosesTheLastVisibleShelf() {
        val rows = listOf(
            "home_genres" to 4,
            "col_popular" to 7,
            "stremio_series" to 11,
        )

        assertEquals(
            "stremio_series" to 11,
            selectTvHomeFastScrollLandingRow(
                visibleFocusRows = rows,
                leadingVisibleIndex = 5,
                direction = 1,
                reachedBottom = true,
            ),
        )
    }

    @Test
    fun upwardLandingUsesTheTopVisibleFocusableShelf() {
        val rows = listOf(
            "home_genres" to 4,
            "col_popular" to 7,
            "stremio_series" to 11,
        )

        assertEquals(
            "col_popular" to 7,
            selectTvHomeFastScrollLandingRow(
                visibleFocusRows = rows,
                leadingVisibleIndex = 6,
                direction = -1,
                reachedBottom = false,
            ),
        )
    }

    @Test
    fun landingReturnsNullWhenNoFocusableShelfIsVisible() {
        assertNull(
            selectTvHomeFastScrollLandingRow(
                visibleFocusRows = emptyList(),
                leadingVisibleIndex = 0,
                direction = 1,
                reachedBottom = false,
            ),
        )
    }
}
