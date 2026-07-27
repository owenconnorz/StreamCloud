package com.streamcloud.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class YtsTorrent(
    val url: String = "",
    val hash: String = "",
    val quality: String = "",
    @SerialName("video_codec") val videoCodec: String = "",
    val type: String = "",
    val size: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val seeds: Int = 0,
    val peers: Int = 0,
) {
    fun magnetLink(title: String, year: Int): String {
        val dn = java.net.URLEncoder.encode(
            if (year > 0) "$title ($year)" else title, "UTF-8"
        )
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
        ).joinToString("&") { "tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:$hash&dn=$dn&$trackers"
    }
}

@Serializable
data class YtsMovie(
    val id: Int = 0,
    val title: String = "",
    val year: Int = 0,
    @SerialName("imdb_code") val imdbCode: String = "",
    @SerialName("medium_cover_image") val coverUrl: String = "",
    val torrents: List<YtsTorrent> = emptyList(),
)

@Serializable
data class YtsMovieData(val movie: YtsMovie? = null)

@Serializable
data class YtsMovieListData(
    @SerialName("movie_count") val movieCount: Int = 0,
    val movies: List<YtsMovie> = emptyList(),
)

@Serializable
data class YtsMovieDetailsResponse(
    val status: String = "",
    val data: YtsMovieData = YtsMovieData(),
)

@Serializable
data class YtsListMoviesResponse(
    val status: String = "",
    val data: YtsMovieListData = YtsMovieListData(),
)

interface YtsApi {
    @GET("movie_details.json")
    suspend fun movieDetails(@Query("imdb_id") imdbId: String): YtsMovieDetailsResponse

    @GET("list_movies.json")
    suspend fun listMovies(
        @Query("query_term") queryTerm: String,
        @Query("limit") limit: Int = 5,
    ): YtsListMoviesResponse
}
