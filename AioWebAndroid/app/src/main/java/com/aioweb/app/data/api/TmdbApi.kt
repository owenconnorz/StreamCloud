package com.aioweb.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class TmdbMovie(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
) {
    val displayTitle: String get() = title ?: name ?: "Untitled"
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
}

@Serializable
data class TmdbListResponse(
    val page: Int = 1,
    val results: List<TmdbMovie> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val name: String? = null,
)

@Serializable
data class TmdbVideosResponse(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
)

interface TmdbApi {
    @GET("3/trending/movie/{window}")
    suspend fun trending(
        @retrofit2.http.Path("window") window: String = "week",
        @Query("api_key") apiKey: String,
    ): TmdbListResponse

    @GET("3/movie/popular")
    suspend fun popular(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/movie/top_rated")
    suspend fun topRated(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/movie/now_playing")
    suspend fun nowPlaying(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    @GET("3/search/movie")
    suspend fun search(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
    ): TmdbListResponse

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

    /** Generic discover endpoint — used by Nuvio-style collections (Marvel, DC, etc.). */
    @GET("3/discover/movie")
    suspend fun discover(
        @Query("api_key") apiKey: String,
        @Query("with_companies") withCompanies: String? = null,
        @Query("with_genres") withGenres: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1,
    ): TmdbListResponse

    /**
     * IMDB → TMDB resolver. Stremio addons identify items by IMDB id
     * (e.g. `tt12345`), but our MovieDetail screen is keyed on the TMDB id.
     * `/find` lets us look them up without a paid Bridge API.
     */
    @GET("3/find/{externalId}")
    suspend fun find(
        @retrofit2.http.Path("externalId") externalId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id",
    ): TmdbFindResponse
}

@kotlinx.serialization.Serializable
data class TmdbFindResponse(
    @kotlinx.serialization.SerialName("movie_results") val movieResults: List<TmdbMovie> = emptyList(),
    @kotlinx.serialization.SerialName("tv_results") val tvResults: List<TmdbMovie> = emptyList(),
)
