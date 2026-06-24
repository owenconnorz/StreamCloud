package com.streamcloud.app.data.api

import com.streamcloud.app.data.network.Net
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

private interface PornhubApi {
    @GET("webmasters/search")
    suspend fun search(
        @Query("search")  query:    String = "",
        @Query("page")    page:     Int    = 1,
        @Query("ordering") ordering: String = "newest",
        @Query("thumbsize") thumbSize: String = "medium",
    ): PhResponse
}

@Serializable
private data class PhResponse(
    val videos: List<PhVideo> = emptyList(),
)

@Serializable
private data class PhVideo(
    @SerialName("video_id")      val videoId:      String = "",
    val title:                                      String = "",
    val duration:                                   String = "",
    val views:                                      Long   = 0,
    val rating:                                     Double = 0.0,
    @SerialName("default_thumb") val defaultThumb:  String = "",
)

object RedtubeRepository {

    private val api: PornhubApi by lazy {
        Net.retrofit("https://www.pornhub.com/").create(PornhubApi::class.java)
    }

    suspend fun search(query: String, page: Int = 1): List<AdultItem> = runCatching {
        api.search(query = query, page = page).videos.map { v ->
            AdultItem(
                id            = v.videoId,
                title         = v.title,
                thumbnail     = v.defaultThumb.ifBlank { null },
                previewImage  = v.defaultThumb.ifBlank { null },
                durationLabel = v.duration.ifBlank { null },
                streamUrl     = null,
                source        = AdultSource.Redtube,
                embedUrl      = "https://www.pornhub.com/embed/${v.videoId}",
                views         = if (v.views > 0) formatViews(v.views) else null,
                rating        = if (v.rating > 0) "%.0f%%".format(v.rating) else null,
            )
        }
    }.getOrElse { emptyList() }

    private fun formatViews(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000     -> "%.1fK".format(n / 1_000.0)
        else           -> n.toString()
    }
}
