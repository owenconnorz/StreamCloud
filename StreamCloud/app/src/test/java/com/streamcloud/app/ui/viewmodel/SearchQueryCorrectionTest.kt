package com.streamcloud.app.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryCorrectionTest {
    @Test
    fun correctsOneLetterMovieTypo() {
        assertEquals(
            "Spider-Man",
            findClosestSearchTitle("spidrman", listOf("Spider-Man", "Batman")),
        )
    }

    @Test
    fun correctsMultipleWordsAgainstTitleTokens() {
        assertEquals(
            "Stranger Things",
            findClosestSearchTitle("strnger thngs", listOf("Stranger Things", "The Last of Us")),
        )
    }

    @Test
    fun doesNotChangeAnExactTitle() {
        assertNull(findClosestSearchTitle("The Last of Us", listOf("The Last of Us")))
    }

    @Test
    fun ignoresVeryShortTerms() {
        assertNull(findClosestSearchTitle("it", listOf("It")))
    }
}