package com.aioweb.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class EpornerThumb(val src: String? = null, val size: String? = null)

/**
 * `all_qualities` is documented but some endpoints return a `sources` array.
 * We accept both via [EpornerSource] list and a flat map.
 */
@Serializable
data class EpornerSource(
    val src: String? = null,
    val type: String? = null,
    val quality: String? = null,
    val height: Int? = null,
)

@Serializable
data class EpornerVideo(
    val id: String,
    val title: String,
    val keywords: String? = null,
    val views: Long = 0,
    val rate: String? = null,
    val url: String,                 // page URL
    val embed: String,               // embed URL — used for in-app WebView fallback only
    @SerialName("default_thumb") val defaultThumb: EpornerThumb? = null,
    @SerialName("length_sec") val lengthSec: Long = 0,
    @SerialName("length_min") val lengthMin: String? = null,
    // Optional: present on detail responses only.
    @SerialName("all_qualities") val allQualities: Map<String, String>? = null,
    val sources: List<EpornerSource>? = null,
) {
    /** Best direct MP4 URL across all known fields, preferring 1080p > 720p > 480p > others. */
    fun bestMp4(): String? {
        val ranking = listOf("1080p", "720p", "480p", "360p", "240p")
        val fromMap = allQualities?.let { qm ->
            ranking.firstNotNullOfOrNull { qm[it] } ?: qm.values.firstOrNull()
        }
        if (!fromMap.isNullOrBlank()) return fromMap
        val fromSources = sources?.let { list ->
            list.sortedByDescending { it.height ?: it.quality?.filter(Char::isDigit)?.toIntOrNull() ?: 0 }
                .firstNotNullOfOrNull { it.src?.takeIf { s -> s.isNotBlank() } }
        }
        return fromSources
    }
}

@Serializable
data class EpornerSearchResponse(
    val count: Int = 0,
    val total_count: Int = 0,
    val per_page: Int = 30,
    val videos: List<EpornerVideo> = emptyList(),
)

interface EpornerApi {
    @GET("api/v2/video/search/")
    suspend fun search(
        @Query("query") query: String,
        @Query("per_page") perPage: Int = 30,
        @Query("page") page: Int = 1,
        @Query("thumbsize") thumbsize: String = "medium",
        @Query("order") order: String = "most-popular",
        @Query("format") format: String = "json",
    ): EpornerSearchResponse

    /** Fetch a single video by id — response includes `all_qualities` MP4 URLs. */
    @GET("api/v2/video/search/")
    suspend fun details(
        @Query("id") id: String,
        @Query("per_page") perPage: Int = 1,
        @Query("thumbsize") thumbsize: String = "medium",
        @Query("format") format: String = "json",
    ): EpornerSearchResponse
}
