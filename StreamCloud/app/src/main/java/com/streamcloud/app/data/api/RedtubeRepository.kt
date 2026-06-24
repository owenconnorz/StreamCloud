package com.streamcloud.app.data.api

import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

@Serializable
private data class RedtubeThumb(
    val size: String? = null,
    val src: String? = null,
)

@Serializable
private data class RedtubeVideoData(
    val video_id: String = "",
    val title: String = "",
    val url: String = "",
    val embed_url: String = "",
    val default_thumb: String = "",
    val duration: String? = null,
    val thumbs: List<RedtubeThumb> = emptyList(),
)

@Serializable
private data class RedtubeVideoWrapper(
    val video: RedtubeVideoData = RedtubeVideoData(),
)

@Serializable
private data class RedtubeSearchResponse(
    val videos: List<RedtubeVideoWrapper> = emptyList(),
    val count: Int = 0,
)

object RedtubeRepository {
    private val client = OkHttpClient()
    private val json   = Net.json

    suspend fun search(
        query: String = "",
        page: Int = 1,
        limit: Int = 30,
    ): List<AdultItem> = withContext(Dispatchers.IO) {
        val q   = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://api.redtube.com/?data=redtube.Videos.searchVideos" +
                  "&search=$q&format=json&thumbsize=medium&page=$page&limit=$limit&ordering=newest"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val body = runCatching { client.newCall(req).execute().body?.string() }
            .getOrNull() ?: return@withContext emptyList()
        val resp = runCatching { json.decodeFromString<RedtubeSearchResponse>(body) }
            .getOrDefault(RedtubeSearchResponse())
        resp.videos.mapNotNull { wrapper ->
            val v = wrapper.video
            if (v.video_id.isBlank()) return@mapNotNull null
            AdultItem(
                id           = v.video_id,
                title        = v.title.ifBlank { "Redtube video" },
                thumbnail    = v.default_thumb.ifBlank { v.thumbs.firstOrNull()?.src },
                previewImage = v.default_thumb.ifBlank { null },
                durationLabel = v.duration,
                streamUrl    = null,
                embedUrl     = v.embed_url.ifBlank { null },
                source       = AdultSource.Redtube,
            )
        }
    }
}
