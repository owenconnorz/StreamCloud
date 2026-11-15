package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.api.TmdbMovie

internal enum class MobileSearchMediaType(val label: String) {
    Movies("Movies"),
    TvShows("TV Shows"),
    Anime("Anime"),
}

internal enum class MobileSearchGenreMode(val label: String) {
    And("AND"),
    Or("OR"),
}

internal enum class MobileSearchSort(val label: String) {
    Popular("Popular"),
    TopRated("Top rated"),
    Newest("Newest"),
}

internal data class MobileSearchFilters(
    val mediaType: MobileSearchMediaType = MobileSearchMediaType.Movies,
    val genres: Set<Int> = emptySet(),
    val genreMode: MobileSearchGenreMode = MobileSearchGenreMode.And,
    val sort: MobileSearchSort = MobileSearchSort.Popular,
    val minRating: Double = 0.0,
    val maxRating: Double = 10.0,
    val minimumVotes: Int = 0,
    val yearStart: Int? = null,
    val yearEnd: Int? = null,
    val exactYear: Int? = null,
    val ageRating: String? = null,
    val language: String? = null,
    val hideWatched: Boolean = false,
) {
    val requiresDiscovery: Boolean
        get() = mediaType != MobileSearchMediaType.Movies ||
            genres.isNotEmpty() ||
            sort != MobileSearchSort.Popular ||
            minRating > 0.0 ||
            maxRating < 10.0 ||
            minimumVotes > 0 ||
            yearStart != null ||
            yearEnd != null ||
            exactYear != null ||
            ageRating != null ||
            language != null ||
            hideWatched
}

internal data class MobileSearchGenre(
    val id: Int,
    val label: String,
    val movieId: Int,
    val tvId: Int?,
)

internal val mobileSearchGenres = listOf(
    MobileSearchGenre(28, "Action", 28, 10759),
    MobileSearchGenre(12, "Adventure", 12, 10759),
    MobileSearchGenre(16, "Animation", 16, 16),
    MobileSearchGenre(35, "Comedy", 35, 35),
    MobileSearchGenre(80, "Crime", 80, 80),
    MobileSearchGenre(99, "Documentary", 99, 99),
    MobileSearchGenre(18, "Drama", 18, 18),
    MobileSearchGenre(10751, "Family", 10751, 10751),
    MobileSearchGenre(14, "Fantasy", 14, 10765),
    MobileSearchGenre(36, "History", 36, 36),
    MobileSearchGenre(27, "Horror", 27, 27),
    MobileSearchGenre(10402, "Music", 10402, null),
    MobileSearchGenre(9648, "Mystery", 9648, 9648),
    MobileSearchGenre(10749, "Romance", 10749, 10749),
    MobileSearchGenre(878, "Sci-Fi", 878, 10765),
    MobileSearchGenre(53, "Thriller", 53, 9648),
    MobileSearchGenre(10752, "War", 10752, 10768),
    MobileSearchGenre(37, "Western", 37, 37),
)

internal data class MobileSearchDiscoveryQuery(
    val withGenres: String?,
    val sortBy: String,
    val voteAverageMin: Double?,
    val voteAverageMax: Double?,
    val voteCountMin: Int?,
    val dateStart: String?,
    val dateEnd: String?,
    val originalLanguage: String?,
    val certificationCountry: String?,
    val certification: String?,
)

internal fun mobileSearchDiscoveryQuery(
    filters: MobileSearchFilters,
    isTv: Boolean,
): MobileSearchDiscoveryQuery {
    val genreIds = buildList {
        filters.genres.forEach { id ->
            val option = mobileSearchGenres.firstOrNull { it.id == id }
            val mappedId = if (isTv) option?.tvId else option?.movieId
            if (mappedId != null) add(mappedId)
        }
        if (filters.mediaType == MobileSearchMediaType.Anime) add(16)
    }.distinct().sorted()
    val withGenres = genreIds
        .takeIf { it.isNotEmpty() }
        ?.joinToString(if (filters.genreMode == MobileSearchGenreMode.And) "," else "|")
    val year = filters.exactYear
    val startYear = year ?: filters.yearStart
    val endYear = year ?: filters.yearEnd
    val language = filters.language
        ?: if (filters.mediaType == MobileSearchMediaType.Anime) "ja" else null

    return MobileSearchDiscoveryQuery(
        withGenres = withGenres,
        sortBy = when (filters.sort) {
            MobileSearchSort.Popular -> "popularity.desc"
            MobileSearchSort.TopRated -> "vote_average.desc"
            MobileSearchSort.Newest -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
        },
        voteAverageMin = filters.minRating.takeIf { it > 0.0 },
        voteAverageMax = filters.maxRating.takeIf { it < 10.0 },
        voteCountMin = filters.minimumVotes.takeIf { it > 0 },
        dateStart = startYear?.let { "%04d-01-01".format(it) },
        dateEnd = endYear?.let { "%04d-12-31".format(it) },
        originalLanguage = language,
        certificationCountry = if (!isTv && filters.ageRating != null) "US" else null,
        certification = filters.ageRating.takeIf { !isTv },
    )
}

internal fun filterMobileSearchTitles(
    items: List<TmdbMovie>,
    isTv: Boolean,
    filters: MobileSearchFilters,
    watchedIds: Set<Long> = emptySet(),
): List<TmdbMovie> {
    val filtered = items.filter { movie ->
        val matchesType = when (filters.mediaType) {
            MobileSearchMediaType.Movies -> !isTv
            MobileSearchMediaType.TvShows -> isTv
            MobileSearchMediaType.Anime -> 16 in movie.genreIds &&
                movie.originalLanguage.equals(filters.language ?: "ja", ignoreCase = true)
        }
        val selectedGenreIds = filters.genres.mapNotNull { id ->
            val option = mobileSearchGenres.firstOrNull { it.id == id }
            if (isTv) option?.tvId else option?.movieId
        }
        val matchesGenres = when {
            selectedGenreIds.isEmpty() -> true
            filters.genreMode == MobileSearchGenreMode.And ->
                selectedGenreIds.all { it in movie.genreIds }
            else -> selectedGenreIds.any { it in movie.genreIds }
        }
        val date = (if (isTv) movie.firstAirDate else movie.releaseDate)
            ?.substringBefore('-')
            ?.toIntOrNull()
        val startYear = filters.exactYear ?: filters.yearStart
        val endYear = filters.exactYear ?: filters.yearEnd

        matchesType &&
            matchesGenres &&
            movie.voteAverage >= filters.minRating &&
            movie.voteAverage <= filters.maxRating &&
            movie.voteCount >= filters.minimumVotes &&
            (startYear == null || (date != null && date >= startYear)) &&
            (endYear == null || (date != null && date <= endYear)) &&
            (filters.language == null ||
                movie.originalLanguage.equals(filters.language, ignoreCase = true)) &&
            (!filters.hideWatched || movie.id !in watchedIds)
    }

    return when (filters.sort) {
        MobileSearchSort.Popular -> filtered.sortedByDescending { it.popularity }
        MobileSearchSort.TopRated -> filtered.sortedWith(
            compareByDescending<TmdbMovie> { it.voteAverage }.thenByDescending { it.voteCount },
        )
        MobileSearchSort.Newest -> filtered.sortedByDescending {
            (if (isTv) it.firstAirDate else it.releaseDate).orEmpty()
        }
    }
}
