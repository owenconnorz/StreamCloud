package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.api.TmdbMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSearchFiltersTest {
    @Test
    fun discoveryQueryMapsGenreSortRatingVotesDatesLanguageAndAgeRating() {
        val filters = MobileSearchFilters(
            genres = setOf(35, 28),
            sort = MobileSearchSort.Newest,
            minRating = 6.0,
            maxRating = 9.5,
            minimumVotes = 100,
            yearStart = 2010,
            yearEnd = 2019,
            ageRating = "PG-13",
            language = "ja",
        )

        val movie = mobileSearchDiscoveryQuery(filters, isTv = false)
        assertEquals("28,35", movie.withGenres)
        assertEquals("primary_release_date.desc", movie.sortBy)
        assertEquals(6.0, movie.voteAverageMin!!, 0.0)
        assertEquals(9.5, movie.voteAverageMax!!, 0.0)
        assertEquals(100, movie.voteCountMin)
        assertEquals("2010-01-01", movie.dateStart)
        assertEquals("2019-12-31", movie.dateEnd)
        assertEquals("ja", movie.originalLanguage)
        assertEquals("US", movie.certificationCountry)
        assertEquals("PG-13", movie.certification)
    }

    @Test
    fun genreOrUsesPipeAndMapsTvGenreIds() {
        val filters = MobileSearchFilters(
            genres = setOf(28, 35),
            genreMode = MobileSearchGenreMode.Or,
            sort = MobileSearchSort.Newest,
        )

        val tv = mobileSearchDiscoveryQuery(filters, isTv = true)

        assertEquals("35|10759", tv.withGenres)
        assertEquals("first_air_date.desc", tv.sortBy)
        assertNull(tv.certification)
        assertNull(tv.certificationCountry)
    }

    @Test
    fun animeDefaultsToAnimationAndJapaneseWithExactYear() {
        val query = mobileSearchDiscoveryQuery(
            MobileSearchFilters(
                mediaType = MobileSearchMediaType.Anime,
                exactYear = 2024,
            ),
            isTv = false,
        )

        assertEquals("16", query.withGenres)
        assertEquals("ja", query.originalLanguage)
        assertEquals("2024-01-01", query.dateStart)
        assertEquals("2024-12-31", query.dateEnd)
    }

    @Test
    fun titleFilteringHonorsGenreModeRatingYearLanguageAndWatchedState() {
        val matching = TmdbMovie(
            id = 1,
            voteAverage = 8.2,
            voteCount = 500,
            releaseDate = "2023-04-01",
            genreIds = listOf(35, 28),
            originalLanguage = "en",
            popularity = 30.0,
        )
        val missingGenre = matching.copy(id = 2, genreIds = listOf(35))
        val watched = matching.copy(id = 3, popularity = 90.0)
        val filters = MobileSearchFilters(
            genres = setOf(35, 28),
            genreMode = MobileSearchGenreMode.And,
            minRating = 7.0,
            maxRating = 9.0,
            minimumVotes = 100,
            yearStart = 2020,
            yearEnd = 2025,
            language = "en",
            hideWatched = true,
        )

        val results = filterMobileSearchTitles(
            items = listOf(matching, missingGenre, watched),
            isTv = false,
            filters = filters,
            watchedIds = setOf(3),
        )

        assertEquals(listOf(1L), results.map { it.id })
        assertFalse(filters.copy(minRating = 9.0).let {
            filterMobileSearchTitles(listOf(matching), false, it).isNotEmpty()
        })
        assertTrue(
            filterMobileSearchTitles(
                items = listOf(matching.copy(originalLanguage = "ja")),
                isTv = false,
                filters = MobileSearchFilters(
                    mediaType = MobileSearchMediaType.Anime,
                    language = "ja",
                ),
            ).isEmpty(),
        )
    }
}
