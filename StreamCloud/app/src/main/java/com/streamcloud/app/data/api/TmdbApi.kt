package com.streamcloud.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

// ─── Season / Episode ────────────────────────────────────────────────────────

@Serializable
data class TmdbTvSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
) {
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("air_date") val airDate: String? = null,
) {
    val stillUrl: String? get() = stillPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    fun displayLabel(): String {
        val parts = mutableListOf<String>()
        if (seasonNumber > 0 && episodeNumber > 0) parts += "S${seasonNumber}E${episodeNumber}"
        name?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ").ifBlank { "Episode $episodeNumber" }
    }
}

@Serializable
data class TmdbSeasonDetail(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val episodes: List<TmdbEpisode> = emptyList(),
)

// ─── Credits ─────────────────────────────────────────────────────────────────

@Serializable
data class TmdbCastMember(
    val id: Long = 0,
    val name: String = "",
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
    val gender: Int = 0,
) {
    val profileUrl: String? get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
    val creditRole: String get() = character?.takeIf { it.isNotBlank() } ?: job ?: ""
}

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCastMember> = emptyList(),
)

// ─── Production / Networks ────────────────────────────────────────────────────

@Serializable
data class TmdbProductionCompany(
    val id: Long = 0,
    val name: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
) {
    val logoUrl: String? get() = logoPath?.let { "https://image.tmdb.org/t/p/w200$it" }
}

@Serializable
data class TmdbNetwork(
    val id: Long = 0,
    val name: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
) {
    val logoUrl: String? get() = logoPath?.let { "https://image.tmdb.org/t/p/w200$it" }
}

@Serializable
data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
data class TmdbCreatedBy(
    val id: Long = 0,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
) {
    val profileUrl: String? get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

// ─── Certification ────────────────────────────────────────────────────────────

@Serializable
data class TmdbReleaseDatesResponse(val results: List<TmdbReleaseDateCountry> = emptyList())

@Serializable
data class TmdbReleaseDateCountry(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
data class TmdbReleaseDate(
    val certification: String = "",
    val type: Int = 0,
)

@Serializable
data class TmdbContentRatingsResponse(val results: List<TmdbContentRating> = emptyList())

@Serializable
data class TmdbContentRating(
    @SerialName("iso_3166_1") val country: String = "",
    val rating: String = "",
)

// ─── Movie / TV detail ────────────────────────────────────────────────────────

@Serializable
data class TmdbMovie(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbProductionCompany> = emptyList(),
    val networks: List<TmdbNetwork> = emptyList(),
    val status: String? = null,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreatedBy> = emptyList(),
    // TV-series fields
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val seasons: List<TmdbTvSeasonSummary> = emptyList(),
) {
    val displayTitle: String get() = title ?: name ?: "Untitled"
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

    /** Formatted runtime string, e.g. "2h 26m" or "45m". */
    fun displayRuntime(): String? {
        val mins = runtime ?: episodeRunTime.firstOrNull() ?: return null
        if (mins <= 0) return null
        val h = mins / 60; val m = mins % 60
        return when { h == 0 -> "${m}m"; m == 0 -> "${h}h"; else -> "${h}h ${m}m" }
    }

    /** "2026" for movies, "2022–2025" or "2022–" for TV. */
    fun displayReleaseInfo(): String? {
        val startYear = (releaseDate ?: firstAirDate)?.substringBefore('-')?.takeIf { it.length == 4 } ?: return null
        if (title != null) return startYear // is a movie
        val endYear = lastAirDate?.substringBefore('-')?.takeIf { it.length == 4 }
        val ongoing = status in listOf("Returning Series", "In Production", "Planned", null)
        return if (endYear != null && !ongoing) "$startYear–$endYear" else "$startYear–"
    }
}

// ─── List responses ────────────────────────────────────────────────────────────

@Serializable
data class TmdbListResponse(
    val page: Int = 1,
    val results: List<TmdbMovie> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

// ─── Video ────────────────────────────────────────────────────────────────────

@Serializable
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val name: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
) {
    val thumbnailUrl: String? get() = if (site.equals("YouTube", ignoreCase = true))
        "https://img.youtube.com/vi/$key/mqdefault.jpg" else null
    val watchUrl: String get() = if (site.equals("YouTube", ignoreCase = true))
        "https://www.youtube.com/watch?v=$key" else key
}

@Serializable
data class TmdbVideosResponse(val results: List<TmdbVideo> = emptyList())

// ─── External IDs ─────────────────────────────────────────────────────────────

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
)

// ─── Find ─────────────────────────────────────────────────────────────────────

@kotlinx.serialization.Serializable
data class TmdbFindResponse(
    @kotlinx.serialization.SerialName("movie_results") val movieResults: List<TmdbMovie> = emptyList(),
    @kotlinx.serialization.SerialName("tv_results") val tvResults: List<TmdbMovie> = emptyList(),
)

// ─── API interface ─────────────────────────────────────────────────────────────

interface TmdbApi {

    @GET("3/trending/movie/{window}")
    suspend fun trending(
        @retrofit2.http.Path("window") window: String = "week",
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/movie/popular")
    suspend fun popular(@Query("api_key") apiKey: String, @Query("page") page: Int = 1): TmdbListResponse

    @GET("3/movie/top_rated")
    suspend fun topRated(@Query("api_key") apiKey: String, @Query("page") page: Int = 1): TmdbListResponse

    @GET("3/movie/now_playing")
    suspend fun nowPlaying(@Query("api_key") apiKey: String, @Query("page") page: Int = 1): TmdbListResponse

    @GET("3/search/movie")
    suspend fun search(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    // ── Movie detail ──────────────────────────────────────────────────────────

    @GET("3/movie/{id}")
    suspend fun details(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbMovie

    @GET("3/movie/{id}/videos")
    suspend fun videos(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbVideosResponse

    @GET("3/movie/{id}/external_ids")
    suspend fun externalIds(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbExternalIds

    @GET("3/movie/{id}/credits")
    suspend fun movieCredits(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbCredits

    @GET("3/movie/{id}/similar")
    suspend fun movieSimilar(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/movie/{id}/release_dates")
    suspend fun movieReleaseDates(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbReleaseDatesResponse

    @GET("3/discover/movie")
    suspend fun discover(
        @Query("api_key") apiKey: String,
        @Query("with_companies") withCompanies: String? = null,
        @Query("with_genres") withGenres: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    // ── TV detail ─────────────────────────────────────────────────────────────

    @GET("3/tv/{id}")
    suspend fun tvDetails(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbMovie

    @GET("3/tv/{id}/videos")
    suspend fun tvVideos(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbVideosResponse

    @GET("3/tv/{id}/external_ids")
    suspend fun tvExternalIds(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbExternalIds

    @GET("3/tv/{id}/credits")
    suspend fun tvCredits(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbCredits

    @GET("3/tv/{id}/similar")
    suspend fun tvSimilar(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/tv/{id}/content_ratings")
    suspend fun tvContentRatings(
        @retrofit2.http.Path("id") id: Long,
        @Query("api_key") apiKey: String,
    ): TmdbContentRatingsResponse

    @GET("3/tv/{id}/season/{seasonNumber}")
    suspend fun tvSeasonDetail(
        @retrofit2.http.Path("id") id: Long,
        @retrofit2.http.Path("seasonNumber") seasonNumber: Int,
        @Query("api_key") apiKey: String,
    ): TmdbSeasonDetail

    // ── Find ──────────────────────────────────────────────────────────────────

    @GET("3/find/{externalId}")
    suspend fun find(
        @retrofit2.http.Path("externalId") externalId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id",
    ): TmdbFindResponse
}
