package com.streamcloud.app.data.api

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BeegPage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

data class BeegResolvedPlayback(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Reads Beeg's public video index and tag-search results using the same public
 * endpoints as its website. Playback uses the stream URL returned by Beeg's
 * own play_url endpoint and does not bypass provider verification checks.
 */
object BeegRepository {
    private const val STORE_URL = "https://store.externulls.com/"
    private const val SEARCH_SOCKET_URL = "wss://search.externulls.com"
    private const val VIDEO_PAGE_BASE_URL = "https://beeg.com/"
    private const val VIDEO_STREAM_BASE_URL = "https://video.beeg.com/"
    private const val THUMBNAIL_BASE_URL = "https://thumbs.externulls.com"
    private const val PAGE_SIZE = 20
    private const val SEARCH_RESULT_LIMIT = 10
    private const val MAX_SEARCH_TAGS = 5

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val searchClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val playbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun resolvePlayback(
        pageUrl: String,
        userAgent: String,
    ): BeegResolvedPlayback = withContext(Dispatchers.IO) {
        val safePageUrl = normalizeBeegVideoPageUrl(pageUrl)
            ?: throw IOException("Beeg did not provide a valid video page.")
        val videoId = safePageUrl.toHttpUrl().encodedPath
            .removePrefix("/-0")
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?.toString()
            ?: throw IOException("Beeg did not provide a valid video ID.")
        val request = Request.Builder()
            .url("$STORE_URL/video/play_url/$videoId")
            .header("Accept", "text/plain, */*")
            .header("Origin", "https://beeg.com")
            .header("Referer", safePageUrl)
            .header("User-Agent", userAgent)
            .apply {
                runCatching { CookieManager.getInstance().getCookie(safePageUrl) }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?.let { header("Cookie", it) }
            }
            .get()
            .build()

        playbackClient.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                throw IOException("Beeg redirected playback. Open the official page and retry.")
            }
            if (response.code == 403) {
                throw IOException(
                    "Beeg requires official age or browser verification. Complete it on Beeg, " +
                        "then retry; StreamCloud does not bypass those checks.",
                )
            }
            if (response.code == 429) {
                throw IOException("Beeg is rate-limiting playback requests. Wait and try again.")
            }
            if (!response.isSuccessful) {
                throw IOException("Beeg could not prepare playback (HTTP ${response.code}).")
            }

            val body = response.body?.string()
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("Beeg returned an empty playback response.")
            val streamUrl = buildNativeStreamUrl(body)
                ?: throw IOException(
                    "Beeg returned a playback response StreamCloud could not read. Retry, or " +
                        "open the video on Beeg to check its availability.",
                )
            BeegResolvedPlayback(
                url = streamUrl,
                headers = buildPlaybackHeaders(userAgent, safePageUrl),
            )
        }
    }

    internal fun browserUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context.applicationContext)
            .replace("; wv", "")
            .replace(" Version/4.0", "")

    internal fun buildNativeStreamUrl(playUrlResponse: String): String? {
        val path = playUrlResponse.trim()
            .removePrefix("/")
            .takeIf { PLAY_URL_PATH_PATTERNS.any { pattern -> pattern.matches(it) } }
            ?: return null
        return "$VIDEO_STREAM_BASE_URL$path"
    }

    internal fun normalizeBeegVideoPageUrl(rawUrl: String): String? {
        val value = rawUrl.trim().takeIf(String::isNotBlank) ?: return null
        val url = runCatching { VIDEO_PAGE_BASE_URL.toHttpUrl().resolve(value) }.getOrNull()
            ?: return null
        return url.toString().takeIf {
            url.scheme == "https" &&
                url.port == 443 &&
                url.host in setOf("beeg.com", "www.beeg.com") &&
                url.username.isBlank() &&
                url.password.isBlank() &&
                BEEG_VIDEO_ID_PATTERN.matches(url.encodedPath)
        }
    }

    internal fun buildPlaybackHeaders(userAgent: String, pageUrl: String): Map<String, String> =
        buildMap {
            put("User-Agent", userAgent)
            put("Referer", pageUrl)
            put("Origin", "https://beeg.com")
            put("Accept", "*/*")
            runCatching { CookieManager.getInstance().getCookie(pageUrl) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { put("Cookie", it) }
        }

    suspend fun fetchPage(query: String, page: Int): BeegPage {
        require(page > 0) { "Page must be positive." }
        val safeQuery = query.trim()
        val tags = if (safeQuery.isBlank()) {
            listOf("index")
        } else {
            searchTags(safeQuery)
        }
        if (tags.isEmpty()) return BeegPage(emptyList(), hasMore = false)

        val url = buildVideoListUrl(tags, page)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Origin", "https://beeg.com")
                .header("Referer", "https://beeg.com/")
                .header("User-Agent", "StreamCloud Android")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                    val wait = retryAfter?.let { " Try again in $it seconds." }.orEmpty()
                    throw IOException("Beeg is rate-limiting requests.$wait")
                }
                if (response.code == 403) {
                    throw IOException(
                        "Beeg requires browser verification. Open Beeg's official page and try again.",
                    )
                }
                if (!response.isSuccessful) {
                    throw IOException("Beeg returned HTTP ${response.code}.")
                }

                val body = response.body?.string()
                    ?: throw IOException("Beeg returned an empty video list.")
                val records = runCatching { json.parseToJsonElement(body).jsonArray }
                    .getOrElse {
                        throw IOException("Beeg returned an unreadable video list.", it)
                    }
                val items = records.mapNotNull(::toAdultItem)
                BeegPage(items, hasMore = records.size >= PAGE_SIZE)
            }
        }
    }

    internal fun buildVideoListUrl(tags: List<String>, page: Int): HttpUrl {
        require(page > 0) { "Page must be positive." }
        val safeTags = tags.distinct().filter { it.matches(TAG_SLUG_PATTERN) }
        require(safeTags.isNotEmpty()) { "Beeg did not provide a valid search tag." }

        return STORE_URL.toHttpUrl().newBuilder()
            .addPathSegment("tag")
            .addPathSegment("videos")
            .addPathSegment(safeTags.joinToString(",").lowercase())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
    }

    internal fun parseSearchTags(response: String): List<String> {
        val root = runCatching { json.parseToJsonElement(response) }.getOrNull()
        val candidates = when (root) {
            is JsonArray -> root
            is JsonObject -> sequenceOf("items", "results", "data")
                .mapNotNull { root[it] as? JsonArray }
                .firstOrNull()
                ?: return emptyList()
            else -> return emptyList()
        }

        return candidates.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val slug = item["tg_slug"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.matches(TAG_SLUG_PATTERN) }
                ?: return@mapNotNull null
            val videoCount = item["tg_videos_count"]?.jsonPrimitive?.intOrNull
            if (videoCount != null && videoCount <= 0) null else slug
        }
            .distinct()
            .take(MAX_SEARCH_TAGS)
    }

    internal fun parseVideoPage(body: String): BeegPage {
        val records = json.parseToJsonElement(body).jsonArray
        val items = records.mapNotNull(::toAdultItem)
        return BeegPage(items, hasMore = records.size >= PAGE_SIZE)
    }

    private suspend fun searchTags(query: String): List<String> = withTimeout(15_000L) {
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(SEARCH_SOCKET_URL)
                .header("Origin", "https://beeg.com")
                .header("User-Agent", "StreamCloud Android")
                .build()

            val socket = searchClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val payload = buildJsonObject {
                            put("type", "search")
                            put("ignore_stats", true)
                            put(
                                "payload",
                                buildJsonObject {
                                    put("Search_string", query)
                                    put("offset", 0)
                                    put("limit", SEARCH_RESULT_LIMIT)
                                },
                            )
                        }
                        if (!webSocket.send(payload.toString()) && continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Beeg search could not be sent."),
                            )
                            webSocket.cancel()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (continuation.isActive) {
                            continuation.resume(parseSearchTags(text))
                        }
                        webSocket.close(1000, "Search complete")
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Beeg search is unavailable.", t),
                            )
                        }
                    }
                },
            )
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }

    private fun toAdultItem(record: kotlinx.serialization.json.JsonElement): AdultItem? {
        val recordObject = record as? JsonObject ?: return null
        val file = recordObject["file"] as? JsonObject ?: return null
        val id = file["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val title = file["data"]
            ?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull {
                it["cd_column"]?.jsonPrimitive?.contentOrNull == "sf_name"
            }
            ?.get("cd_value")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null

        val thumbnailOffset = recordObject["fc_facts"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("fc_thumbs")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.contentOrNull
        val thumbnail = thumbnailOffset
            ?.takeIf { it.matches(THUMB_OFFSET_PATTERN) }
            ?.let { "$THUMBNAIL_BASE_URL/videos/$id/$it.webp?size=480x270" }
        val duration = file["fl_duration"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it > 0 }

        return AdultItem(
            id = id.toString(),
            title = title,
            thumbnail = thumbnail,
            previewImage = thumbnail,
            durationLabel = duration?.let(::formatDuration),
            streamUrl = null,
            source = AdultSource.Beeg,
            embedUrl = "$VIDEO_PAGE_BASE_URL-0$id",
            views = recordObject["fc_facts"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("fc_st_views")
                ?.jsonPrimitive
                ?.contentOrNull,
        )
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remainingSeconds = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
        } else {
            "%d:%02d".format(minutes, remainingSeconds)
        }
    }

    private val TAG_SLUG_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
    private val THUMB_OFFSET_PATTERN = Regex("\\d{1,18}")
    private val BEEG_VIDEO_ID_PATTERN = Regex("/-0[1-9]\\d{0,17}/?")
    private val PLAY_URL_PATH_PATTERNS = listOf(
        // Current Beeg response: key/data/media/multi template ending in an HLS playlist.
        Regex(
            "key=[A-Za-z0-9_-]{8,128},end=\\d{1,12},limit=\\d{1,4}" +
                "/data=[A-Za-z0-9._-]{1,160}" +
                "/media=[A-Za-z0-9_-]{1,32}" +
                "/multi=[A-Za-z0-9_.,:=+-]{1,4096}" +
                "/_TPL_/\\d{1,18}\\.mp4\\.m3u8",
        ),
        // Keep support for the earlier direct playlist/file response format.
        Regex(
            "key=[A-Za-z0-9_-]{8,128},end=\\d{1,12},limit=\\d{1,4}" +
                "/data=[A-Za-z0-9._-]{1,160}\\.(?:m3u8|mp4)",
        ),
    )
}
