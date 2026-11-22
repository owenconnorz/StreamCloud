package com.streamcloud.app.ui.screens

import androidx.compose.ui.unit.dp
import com.streamcloud.app.ui.viewmodel.HeroBannerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviesScreenTvLandingPolicyTest {
    @Test
    fun continueWatchingViewportAlsoFitsTheFollowingMovieShelf() {
        assertEquals(
            320.dp,
            calculateTvHomeRowsViewportHeight(
                continueWatchingRowHeight = 138.dp,
                hasContinueWatching = true,
                hasTopTen = true,
            ),
        )
    }

    @Test
    fun continueWatchingViewportExpandsToFitTheFollowingShelf() {
        assertEquals(
            390.dp,
            calculateTvHomeRowsViewportHeight(
                continueWatchingRowHeight = 210.dp,
                hasContinueWatching = true,
                hasTopTen = false,
            ),
        )
    }

    @Test
    fun verticalTvMovementTargetsTheNextDistinctShelf() {
        val shelves = listOf(
            "continue_watching" to 2,
            "col_netflix" to 5,
            "stremio_trending" to 8,
        )

        assertEquals("col_netflix" to 5, selectNextTvHomeFocusRowAfter(shelves, 2))
        assertEquals("continue_watching" to 2, selectPreviousTvHomeFocusRowBefore(shelves, 5))
    }

    @Test
    fun featuredTvHeroSkipsContinueWatchingTitles() {
        val featured = selectTvFeaturedHeroItems(
            items = listOf(
                HeroBannerItem(imageUrl = "grinch.jpg", title = "The Grinch", tmdbId = 1L),
                HeroBannerItem(imageUrl = "movie.jpg", title = "Featured", tmdbId = 2L),
            ),
            continueWatchingIdentities = setOf(tvHomeHeroIdentity(1L, "movie")),
            resolvedStremioTmdbIds = emptyMap(),
        )

        assertEquals(listOf("Featured"), featured.map { it.title })
        assertFalse(featured.single().isContinueWatching)
    }

    @Test
    fun featuredTvHeroMarksContinueWatchingWhenItIsTheOnlyAvailableChoice() {
        val featured = selectTvFeaturedHeroItems(
            items = listOf(
                HeroBannerItem(imageUrl = "grinch.jpg", title = "The Grinch", tmdbId = 1L),
            ),
            continueWatchingIdentities = setOf(tvHomeHeroIdentity(1L, "movie")),
            resolvedStremioTmdbIds = emptyMap(),
        )

        assertTrue(featured.single().isContinueWatching)
    }

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
    fun heroActionsHideOnceTheHomeShelfListScrolls() {
        assertTrue(
            shouldShowTvHeroActions(
                queryIsBlank = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
            ),
        )
        assertTrue(
            shouldShowTvHeroActions(
                queryIsBlank = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 16,
            ),
        )
        assertFalse(
            shouldShowTvHeroActions(
                queryIsBlank = true,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
            ),
        )
        assertFalse(
            shouldShowTvHeroActions(
                queryIsBlank = true,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 17,
            ),
        )
        assertFalse(
            shouldShowTvHeroActions(
                queryIsBlank = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
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
