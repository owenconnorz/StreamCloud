package com.streamcloud.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class PornMzPage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

/**
 * Reads PornMZ's public WordPress posts feed. Playback stays on each post's
 * official page so its own player, verification, and access checks are retained.
 */
object PornMzRepository {
    private const val BASE_URL = "https://pornmz.com/"
    private const val PAGE_SIZE = 30

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchPage(query: String, page: Int): PornMzPage = withContext(Dispatchers.IO) {
        require(page > 0) { "Page must be positive." }

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("wp-json/wp/v2/posts")
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("_embed", "1")
            .apply {
                query.trim().takeIf(String::isNotBlank)?.let {
                    addQueryParameter("search", it)
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "StreamCloud Android")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 429) {
                throw IOException("PornMZ is rate-limiting requests. Try again later.")
            }
            if (!response.isSuccessful) {
                throw IOException("PornMZ returned HTTP ${response.code}.")
            }

            val body = response.body?.string()
                ?: throw IOException("PornMZ returned an empty response.")
            val posts = runCatching {
                json.decodeFromString<List<PornMzPost>>(body)
            }.getOrElse {
                throw IOException("PornMZ returned an unreadable video list.", it)
            }
            val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull()

            PornMzPage(
                items = posts.mapNotNull(::toAdultItem),
                hasMore = totalPages?.let { page < it } ?: (posts.size == PAGE_SIZE),
            )
        }
    }

    private fun toAdultItem(post: PornMzPost): AdultItem? {
        val title = Jsoup.parse(post.title.rendered).text().trim()
        val pageUrl = securePornMzUrl(post.link) ?: return null
        if (title.isBlank()) return null

        val thumbnail = post.embedded
            ?.featuredMedia
            ?.firstOrNull()
            ?.sourceUrl
            ?.let(::secureHttpsUrl)

        return AdultItem(
            id = post.id.toString(),
            title = title,
            thumbnail = thumbnail,
            previewImage = thumbnail,
            durationLabel = null,
            streamUrl = null,
            source = AdultSource.PornMz,
            embedUrl = pageUrl,
        )
    }

    private fun securePornMzUrl(rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return rawUrl.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                host in setOf("pornmz.com", "www.pornmz.com") &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }
    }

    private fun secureHttpsUrl(rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        return rawUrl.takeIf {
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }
    }

    @Serializable
    private data class PornMzPost(
        val id: Long,
        val link: String,
        val title: PornMzRendered,
        @SerialName("_embedded")
        val embedded: PornMzEmbedded? = null,
    )

    @Serializable
    private data class PornMzRendered(
        val rendered: String,
    )

    @Serializable
    private data class PornMzEmbedded(
        @SerialName("wp:featuredmedia")
        val featuredMedia: List<PornMzMedia> = emptyList(),
    )

    @Serializable
    private data class PornMzMedia(
        @SerialName("source_url")
        val sourceUrl: String? = null,
    )
}
