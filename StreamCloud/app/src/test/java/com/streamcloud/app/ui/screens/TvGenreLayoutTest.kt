package com.streamcloud.app.ui.screens

import androidx.compose.ui.unit.dp
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
}
