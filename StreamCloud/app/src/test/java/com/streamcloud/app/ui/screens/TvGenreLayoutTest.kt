package com.streamcloud.app.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvGenreLayoutTest {
    @Test
    fun sixGenreCardsIncludingAdultFitOnCommonTvWidths() {
        listOf(683.dp, 853.dp, 1280.dp).forEach { screenWidth ->
            val cardWidth = calculateTvGenreCardWidth(screenWidth, genreCount = 6)
            val totalWidth = cardWidth.value * 6 + 10.dp.value * 5 + 48.dp.value

            assertTrue(
                "Six genre cards should fit within $screenWidth, but need $totalWidth dp",
                totalWidth <= screenWidth.value + 0.01f,
            )
        }
    }

    @Test
    fun genreCardWidthRemainsCappedOnWideScreens() {
        assertTrue(
            calculateTvGenreCardWidth(1920.dp, genreCount = 6) <= 220.dp,
        )
    }

    @Test
    fun mobileGenreCardsUseReadableCarouselWidths() {
        listOf(360.dp, 393.dp, 432.dp).forEach { screenWidth ->
            val cardWidth = calculateMobileGenreCardWidth(screenWidth)

            assertTrue(cardWidth >= 144.dp)
            assertTrue(cardWidth <= 176.dp)
            assertTrue(cardWidth < screenWidth)
        }
    }

    @Test
    fun downTargetsNextActualFocusableRowAndSkipsHeadings() {
        val rows = listOf(
            "continue_watching" to 4,
            "home_genres" to 6,
            "col_movies" to 9,
        )

        assertEquals("home_genres" to 6, selectAdjacentTvHomeFocusRow(rows, "continue_watching", 1))
        assertEquals("col_movies" to 9, selectAdjacentTvHomeFocusRow(rows, "home_genres", 1))
    }

    @Test
    fun downFindsNearestFocusableRowAfterTheSourceLeavesTheViewport() {
        val visibleRows = listOf(
            "home_genres" to 6,
            "col_movies" to 9,
        )

        assertEquals(
            "home_genres" to 6,
            selectNextTvHomeFocusRowAfter(visibleRows, sourceListIndex = 4),
        )
        assertEquals(
            "col_movies" to 9,
            selectNextTvHomeFocusRowAfter(visibleRows, sourceListIndex = 6),
        )
        assertNull(selectNextTvHomeFocusRowAfter(visibleRows, sourceListIndex = 9))
    }

    @Test
    fun heroDownPrefersContinueWatchingBeforeGenres() {
        assertEquals(
            "continue_watching",
            selectFirstTvHomeFocusRowKey(hasContinueWatching = true, hasGenres = true),
        )
        assertEquals(
            "home_genres",
            selectFirstTvHomeFocusRowKey(hasContinueWatching = false, hasGenres = true),
        )
        assertNull(selectFirstTvHomeFocusRowKey(hasContinueWatching = false, hasGenres = false))
    }

    @Test
    fun heroFallsThroughToFirstAvailableShelfWhenGenresAreHidden() {
        assertEquals(
            "col_popular",
            selectFirstTvHomeFocusRowKey(
                hasContinueWatching = false,
                hasGenres = false,
                fallbackRowKey = "col_popular",
            ),
        )
    }

    @Test
    fun staleShelfIsNotUsedAfterFocusLeavesIt() {
        assertEquals(
            "home_genres",
            activeTvHomeFocusSourceRowKey("home_genres", rowHasFocus = true),
        )
        assertNull(activeTvHomeFocusSourceRowKey("home_genres", rowHasFocus = false))
        assertNull(activeTvHomeFocusSourceRowKey(null, rowHasFocus = false))
        assertEquals(
            null,
            activeTvHomeFocusSourceRowKey(focusedRowKey = null, rowHasFocus = false),
        )
    }

    @Test
    fun verticalShelfHandoffsPreserveTheFocusedColumnAndClampShortRows() {
        assertEquals(4, resolveTvHomeFocusItemIndex(false, rememberedItemIndex = 4, itemCount = 8))
        assertEquals(0, resolveTvHomeFocusItemIndex(true, rememberedItemIndex = 4, itemCount = 8))
        assertEquals(3, resolveTvHomeFocusItemIndex(false, rememberedItemIndex = 7, itemCount = 4))
        assertEquals(
            4,
            resolveTvHomeFocusItemIndex(
                focusFirstItem = false,
                rememberedItemIndex = 1,
                itemCount = 8,
                preferredItemIndex = 4,
            ),
        )
        assertEquals(
            2,
            resolveTvHomeFocusItemIndex(
                focusFirstItem = false,
                rememberedItemIndex = 0,
                itemCount = 3,
                preferredItemIndex = 4,
            ),
        )
        assertEquals(
            0,
            resolveTvHomeFocusItemIndex(
                focusFirstItem = true,
                rememberedItemIndex = 3,
                itemCount = 8,
                preferredItemIndex = 4,
            ),
        )
        assertNull(resolveTvHomeFocusItemIndex(false, rememberedItemIndex = 0, itemCount = 0))

        assertTrue(shouldRevealTvHomeFocusItem(itemIndex = 4, visibleItemIndices = listOf(5, 6)))
        assertFalse(shouldRevealTvHomeFocusItem(itemIndex = 4, visibleItemIndices = listOf(3, 4, 5)))
    }

    @Test
    fun upReturnsToPreviousFocusableRow() {
        val rows = listOf(
            "continue_watching" to 4,
            "home_genres" to 6,
            "col_movies" to 9,
        )

        assertEquals("home_genres" to 6, selectAdjacentTvHomeFocusRow(rows, "col_movies", -1))
        assertEquals("continue_watching" to 4, selectAdjacentTvHomeFocusRow(rows, "home_genres", -1))
        assertNull(selectAdjacentTvHomeFocusRow(rows, "missing", -1))
        assertNull(selectAdjacentTvHomeFocusRow(rows, "home_genres", 0))
    }

    @Test
    fun upFindsNearestEarlierShelfAfterSourceHasScrolledOutOfView() {
        val visibleRows = listOf(
            "older_shelf" to 2,
            "nearest_shelf" to 7,
        )

        assertEquals(
            "nearest_shelf" to 7,
            selectPreviousTvHomeFocusRowBefore(visibleRows, sourceListIndex = 9),
        )
    }

    @Test
    fun upSkipsVisibleRowsThatAreAfterTheRememberedSourcePosition() {
        val visibleRows = listOf(
            "target_shelf" to 5,
            "later_shelf" to 8,
        )

        assertEquals(
            "target_shelf" to 5,
            selectPreviousTvHomeFocusRowBefore(visibleRows, sourceListIndex = 7),
        )
        assertNull(selectPreviousTvHomeFocusRowBefore(visibleRows, sourceListIndex = 5))
    }
}
