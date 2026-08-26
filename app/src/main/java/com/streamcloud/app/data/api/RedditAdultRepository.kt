package com.streamcloud.app.data.api

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

/** Thrown when a subreddit is private or quarantined. */
class RedditAuthRequiredException(message: String) : Exception(message)

/** Thrown when Reddit returns HTTP 429. */
class RedditRateLimitException(message: String) : Exception(message)

data class AdultItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val previewImage: String?,
    val durationLabel: String?,
    val streamUrl: String?,
    val audioUrl: String? = null,
    val isVideo: Boolean = true,
    val isGallery: Boolean = false,
    val source: AdultSource,
    val epornerId: String? = null,
    val embedUrl: String? = null,
    val views: String? = null,
    val rating: String? = null,
    val tags: String? = null,
)

enum class AdultSource(val label: String) {
    Eporner("Eporner"),
    Pornhub("Pornhub"),
    Reddit("Reddit"),
    RedGifs("RedGifs"),
}

object RedditAdultRepository {

    private const val USER_AGENT    = "android:com.streamcloud.app:v1.0.0 (by /u/streamcloud_app)"

    /**
     * Remove only Reddit cookies so signing out here does not disconnect the
     * other WebView-backed accounts in StreamCloud.
     */
    fun clearSessionCookies() {
        val manager = CookieManager.getInstance()
        val hosts = listOf(
            "https://www.reddit.com",
            "https://old.reddit.com",
            "https://reddit.com",
        )
        val cookieNames = hosts.flatMap { host ->
            runCatching { manager.getCookie(host).orEmpty() }
                .getOrDefault("")
                .split(';')
                .mapNotNull { cookie ->
                    cookie.substringBefore('=').trim().takeIf(String::isNotBlank)
                }
        }.distinct()
        cookieNames.forEach { name ->
            hosts.forEach { host ->
                manager.setCookie(
                    host,
                    "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure",
                )
            }
            manager.setCookie(
                "https://reddit.com",
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.reddit.com; Path=/; Secure",
            )
        }
        manager.flush()
    }

    // Dedicated Json parser: coerceInputValues handles null for non-nullable fields
    // (Reddit sometimes sends "is_gallery": null, "is_video": null, etc.)
    private val redditJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient          = true
        coerceInputValues  = true
    }

    // Dedicated client: no cookie jar, no BrowserHeaders interceptor
    private val oauthClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20,  TimeUnit.SECONDS)
            // Force HTTP/1.1: Reddit's oauth.reddit.com returns 500 with HTTP/2 from some Android OkHttp builds
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /** Fetch a page using the authenticated WebView cookie jar, with old.reddit as a fallback. */
    suspend fun fetch(
        subreddit: String,
        sort: String = "hot",
        after: String? = null,
    ): Pair<List<AdultItem>, String?> {
        val clean = subreddit.removePrefix("r/").trim()
        require(clean.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid subreddit" }
        val safeSort = sort.takeIf { it in setOf("hot", "new", "top", "rising") } ?: "hot"

        return withContext(Dispatchers.IO) {
            val cookie = runCatching {
                CookieManager.getInstance().getCookie("https://www.reddit.com")
            }.getOrNull().orEmpty()
            val endpoints = listOf("https://www.reddit.com", "https://old.reddit.com")
            var lastCode = 0
            var lastMessage = "No Reddit endpoint responded"
            for (base in endpoints) {
                val url = "$base/r/$clean/$safeSort.json".toHttpUrl().newBuilder()
                    .addQueryParameter("limit", "50")
                    .addQueryParameter("raw_json", "1")
                    .addQueryParameter("include_over_18", "on")
                    .apply { after?.let { addQueryParameter("after", it) } }
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .apply {
                        if (cookie.isNotBlank()) header("Cookie", cookie)
                    }
                    .build()
                oauthClient.newCall(request).execute().use { response ->
                    lastCode = response.code
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful && bodyStr.isNotBlank()) {
                        return@withContext parseListing(clean, bodyStr)
                    }
                    lastMessage = bodyStr.take(160).ifBlank { response.message }
                    if (response.code == 429) {
                        throw RedditRateLimitException("Reddit rate limit. Please wait a moment.")
                    }
                }
            }
            when (lastCode) {
                401, 403 -> throw RedditAuthRequiredException(
                    if (cookie.isBlank()) {
                        "Reddit requires sign-in to load r/$clean."
                    } else {
                        "Reddit denied r/$clean. Sign in again or try another subreddit."
                    },
                )
                404 -> throw RedditAuthRequiredException("r/$clean was not found.")
                else -> throw Exception("Reddit HTTP $lastCode: $lastMessage")
            }
        }
    }

    private fun parseListing(clean: String, bodyStr: String): Pair<List<AdultItem>, String?> {
        val listing = redditJson.decodeFromString<RedditListing>(bodyStr)
        val children = listing.data?.children.orEmpty()
        val validData = children.mapNotNull { it.data }
        val items = validData.mapNotNull { post ->
            try { post.toAdultItem() } catch (_: Exception) { null }
        }
        if (items.isEmpty()) {
            val apiCount = children.size
            val parsed = validData.size
            if (apiCount == 0) throw Exception("r/$clean returned 0 posts")
            if (parsed == 0) throw Exception("r/$clean: $apiCount posts received but 0 parsed")
            throw Exception("r/$clean: $apiCount received, $parsed parsed, 0 media posts")
        }
        return items to listing.data?.after
    }

    // ── Post → AdultItem mapping (unchanged from before) ───────────────────

    private fun RedditPost.toAdultItem(): AdultItem? {
        val redditVideo  = media?.dashVideo() ?: secure_media?.dashVideo()
        val previewVideo = (preview?.get("reddit_video_preview") as? JsonObject)?.fallbackUrl()

        var streamUrl: String? = null
        var audioUrl:  String? = null
        var isVideo            = false

        if (redditVideo != null) {
            streamUrl = redditVideo.fallbackUrl?.removeSuffix("?source=fallback")
            val base  = streamUrl
                ?.replace(Regex("DASH_\\d+\\.mp4.*$"),  "")
                ?.replace(Regex("DASH_[^/]+\\.mp4.*$"), "")
            if (!base.isNullOrBlank() && redditVideo.hasAudio != false) {
                audioUrl = "${base}DASH_AUDIO_128.mp4"
            }
            isVideo = true
        } else if (previewVideo != null) {
            streamUrl = previewVideo.removeSuffix("?source=fallback")
            isVideo   = true
        } else if (url.contains("redgifs.com", ignoreCase = true)) {
            val match = Regex(
                "redgifs\\.com/(?:watch/)?([\\w-]+)", RegexOption.IGNORE_CASE
            ).find(url)
            streamUrl = if (match != null)
                "https://www.redgifs.com/ifr/${match.groupValues[1]}"
            else url
            isVideo = true
        } else if (url.endsWith(".mp4", true) || url.endsWith(".webm", true)) {
            streamUrl = url
            isVideo   = true
        } else if (url.endsWith(".gifv", true)) {
            streamUrl = url.replaceFirst(".gifv", ".mp4", ignoreCase = true)
            isVideo   = true
        }

        val thumb = listOfNotNull(
            preview?.previewImageSource(),
            thumbnail?.takeIf {
                it != "self" && it != "default" && it != "nsfw" && it.startsWith("http")
            },
            // i.redd.it image URLs (with or without extension)
            url.takeIf { it.startsWith("https://i.redd.it/") },
            url.takeIf { it.startsWith("https://i.imgur.com/") },
            url.takeIf {
                it.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE))
            },
        ).firstOrNull()

        if (!isVideo && thumb == null && is_gallery != true) return null

        return AdultItem(
            id           = id,
            title        = title.ifBlank { "r/$subreddit" },
            thumbnail    = thumb,
            previewImage = thumb,
            durationLabel = null,
            streamUrl    = streamUrl,
            audioUrl     = audioUrl,
            isVideo      = isVideo,
            isGallery    = is_gallery == true,
            source       = AdultSource.Reddit,
            tags         = subreddit.ifBlank { null },
        )
    }

    private data class DashVideo(
        val fallbackUrl: String?,
        val hlsUrl: String?,
        val hasAudio: Boolean?,
    )

    private fun JsonObject.dashVideo(): DashVideo? {
        val v = (this["reddit_video"] as? JsonObject) ?: return null
        return DashVideo(
            fallbackUrl = (v["fallback_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            hlsUrl      = (v["hls_url"]      as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            hasAudio    = (v["has_audio"]     as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull,
        )
    }

    private fun JsonObject.fallbackUrl(): String? =
        (this["fallback_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.previewImageSource(): String? = runCatching {
        val images = (this["images"] as? kotlinx.serialization.json.JsonArray) ?: return@runCatching null
        val first  = images.firstOrNull()?.jsonObject ?: return@runCatching null
        val source = (first["source"] as? JsonObject) ?: return@runCatching null
        (source["url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.getOrNull()
}

object RedditAdultSubs {
    // All verified working with Reddit API (server IP confirmed, HTTP 200 + posts).
    // Asian_Hotties and nsfw_videos removed (HTTP 404 — banned).
    val PRESETS: List<Pair<String, String>> = listOf(
        "r/nsfw"         to "nsfw",
        "r/gonewild"     to "gonewild",
        "r/RealGirls"    to "RealGirls",
        "r/Amateur"      to "amateur",
        "r/NSFW_GIF"     to "nsfw_gif",
        "r/porn"         to "porn",
        "r/LegalTeens"   to "LegalTeens",
        "r/collegesluts" to "collegesluts",
        "r/Boobies"      to "Boobies",
        "r/ass"          to "ass",
        "r/pawg"         to "pawg",
        "r/thick"        to "thick",
        "r/milf"         to "milf",
        "r/OnOff"        to "OnOff",
        "r/latinas"      to "latinas",
        "r/ebony"        to "ebony",
        "r/cumsluts"     to "cumsluts",
        "r/petitegonewild" to "petitegonewild",
    )
}
