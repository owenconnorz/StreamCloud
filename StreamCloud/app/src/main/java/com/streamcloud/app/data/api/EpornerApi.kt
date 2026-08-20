package com.streamcloud.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import okhttp3.ResponseBody

@Serializable
data class EpornerThumb(val src: String? = null, val size: String? = null)

@Serializable
data class EpornerSource(
    val src: String? = null,
    val type: String? = null,
    val quality: String? = null,
    val height: Int? = null,
)

@Serializable
data class EpornerAdaptiveSource(
    val auto: EpornerSource? = null,
)

@Serializable
data class EpornerPlaybackSources(
    val hls: EpornerAdaptiveSource? = null,
    val mp4: Map<String, EpornerSource> = emptyMap(),
)

@Serializable
data class EpornerPlaybackResponse(
    val sources: EpornerPlaybackSources? = null,
    val available: Boolean? = null,
    val message: String? = null,
)

data class EpornerEmbedConfig(
    val videoId: String,
    val encodedHash: String,
)

/**
 * The public search API now omits direct stream URLs. Eporner's embedded Video.js player fetches
 * short-lived media sources using these two values from its embed page.
 */
fun parseEpornerEmbedConfig(page: String): EpornerEmbedConfig? {
    val videoId = Regex("""EP\.video\.player\.vid\s*=\s*['"]([^'"]+)""")
        .find(page)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: return null
    val rawHash = Regex("""EP\.video\.player\.hash\s*=\s*['"]([0-9a-fA-F]{32})""")
        .find(page)?.groupValues?.getOrNull(1)
        ?: return null
    val encodedHash = rawHash.chunked(8)
        .joinToString(separator = "") { chunk -> chunk.toLong(16).toString(36) }
    return EpornerEmbedConfig(videoId, encodedHash)
}

fun EpornerPlaybackResponse.bestPlayableSource(): EpornerSource? {
    val playbackSources = sources ?: return null
    playbackSources.hls?.auto?.takeIf { !it.src.isNullOrBlank() }?.let { return it }
    return playbackSources.mp4
        .entries
        .sortedByDescending { (quality, source) ->
            source.height ?: quality.filter(Char::isDigit).toIntOrNull() ?: 0
        }
        ?.firstNotNullOfOrNull { (_, source) -> source.takeIf { !it.src.isNullOrBlank() } }
}

@Serializable
data class EpornerVideo(
    val id: String,
    val title: String,
    val keywords: String? = null,
    val views: Long = 0,
    val rate: String? = null,
    val url: String,
    val embed: String,
    @SerialName("default_thumb") val defaultThumb: EpornerThumb? = null,
    @SerialName("length_sec") val lengthSec: Long = 0,
    @SerialName("length_min") val lengthMin: String? = null,

    @SerialName("all_qualities") val allQualities: Map<String, String>? = null,
    val sources: List<EpornerSource>? = null,
) {

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

@Serializable
data class EpornerCategory(
    val id: String = "",
    val slug: String? = null,
    val title: String = "",
    val count: Int = 0,
)

@Serializable
data class EpornerCategoryResponse(
    val count: Int = 0,
    val categories: List<EpornerCategory> = emptyList(),
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

    @GET("api/v2/video/search/")
    suspend fun details(
        @Query("id") id: String,
        @Query("per_page") perPage: Int = 1,
        @Query("thumbsize") thumbsize: String = "medium",
        @Query("format") format: String = "json",
    ): EpornerSearchResponse

    @GET
    suspend fun embedPage(@Url url: String): ResponseBody

    @GET("xhr/video/{videoId}")
    suspend fun playbackSources(
        @Path("videoId") videoId: String,
        @Query("hash") hash: String,
        @Query("domain") domain: String = "www.eporner.com",
        @Query("pixelRatio") pixelRatio: Int = 1,
        @Query("playerWidth") playerWidth: Int = 960,
        @Query("playerHeight") playerHeight: Int = 540,
        @Query("fallback") fallback: Boolean = false,
        @Query("embed") embed: Boolean = true,
        @Query("supportedFormats") supportedFormats: String = "hls,mp4",
        @Query("_") requestTimestamp: Long = System.currentTimeMillis(),
    ): EpornerPlaybackResponse

    @GET("api/v2/category/list/")
    suspend fun categories(
        @Query("per_page") perPage: Int = 100,
        @Query("thumbsize") thumbsize: String = "medium",
        @Query("format") format: String = "json",
        @Query("page") page: Int = 1,
    ): EpornerCategoryResponse
}
