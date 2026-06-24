package com.streamcloud.app.data.api

import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.net.URLEncoder

object RedtubeRepository {

    private val client = Net.okHttp()
    private val json   = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    private data class PhResponse(
        val videos: List<PhVideo> = emptyList(),
    )

    @Serializable
    private data class PhVideo(
        @SerialName("video_id")     val videoId:      String = "",
        val title:                                     String = "",
        val duration:                                  String = "",
        val views:                                     Long   = 0,
        val rating:                                    Double = 0.0,
        @SerialName("default_thumb") val defaultThumb: String = "",
    )

    suspend fun search(query: String, page: Int = 1): List<AdultItem> =
        withContext(Dispatchers.IO) {
            val q = if (query.isBlank()) ""
                    else "&search=${URLEncoder.encode(query, "UTF-8")}"
            val url = "https://www.pornhub.com/webmasters/search" +
                      "?page=$page&ordering=newest&thumbsize=medium$q"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            runCatching {
                json.decodeFromString<PhResponse>(body).videos.map { v ->
                    AdultItem(
                        id           = v.videoId,
                        title        = v.title,
                        thumbnail    = v.defaultThumb.ifBlank { null },
                        previewImage = v.defaultThumb.ifBlank { null },
                        durationLabel = v.duration.ifBlank { null },
                        streamUrl    = null,
                        source       = AdultSource.Redtube,
                        embedUrl     = "https://www.pornhub.com/embed/${v.videoId}",
                        views        = if (v.views > 0) formatViews(v.views) else null,
                        rating       = if (v.rating > 0) "%.0f%%".format(v.rating) else null,
                    )
                }
            }.getOrElse { emptyList() }
        }

    private fun formatViews(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000     -> "%.1fK".format(n / 1_000.0)
        else           -> n.toString()
    }
}
