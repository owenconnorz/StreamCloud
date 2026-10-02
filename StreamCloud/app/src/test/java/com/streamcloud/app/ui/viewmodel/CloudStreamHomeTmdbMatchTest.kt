package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.api.TmdbMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudStreamHomeTmdbMatchTest {
    @Test
    fun matchingTitleAndYearWinsOverOtherVersions() {
        val results = listOf(
            TmdbMovie(id = 11, title = "The Example", releaseDate = "2018-01-01"),
            TmdbMovie(id = 22, title = "The Example", releaseDate = "2024-01-01"),
        )

        val match = bestCloudStreamHomeTmdbMatch(results, "The Example", 2024)

        assertEquals(22L, match?.id)
    }

    @Test
    fun tvSearchUsesFirstAirYearWhenChoosingMatch() {
        val results = listOf(
            TmdbMovie(id = 31, name = "The Example", firstAirDate = "2011-01-01"),
            TmdbMovie(id = 32, name = "The Example", firstAirDate = "2021-01-01"),
        )

        val match = bestCloudStreamHomeTmdbMatch(results, "The Example", 2021)

        assertEquals(32L, match?.id)
    }

    @Test
    fun unrelatedSearchResultsDoNotProduceATrailerMatch() {
        val results = listOf(
            TmdbMovie(id = 41, title = "A Different Film"),
            TmdbMovie(id = 42, title = "Another Film"),
        )

        assertNull(bestCloudStreamHomeTmdbMatch(results, "The Example", null))
    }
}