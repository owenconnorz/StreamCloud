package com.streamcloud.app.data.api

import android.util.Base64
import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
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
    Reddit("Reddit"),
    Redtube("PornHub"),
}

object RedditAdultRepository {

    // ── Reddit OAuth2 client credentials (same app used in AioWeb) ──────────
    private const val CLIENT_ID     = "KvLG0eQTdPDIf_Buo-gkww"
    private const val CLIENT_SECRET = "BCRKFdWhHJ_Ckifv-guBVixUfQA__w"
    private const val USER_AGENT    = "android:com.streamcloud.app:v1.0.0 (by /u/streamcloud_app)"

    // In-memory token cache — same pattern as AioWeb's cachedToken
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry: Long    = 0L

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

    /** Fetch (or return cached) an application-only Bearer token. */
    private suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { tokenExpiry > now }?.let { return@withContext it }

        val credentials = Base64.encodeToString(
            "$CLIENT_ID:$CLIENT_SECRET".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .build()
        val request = Request.Builder()
            .url("https://www.reddit.com/api/v1/access_token")
            .post(body)
            .header("Authorization", "Basic $credentials")
            .header("User-Agent", USER_AGENT)
            .build()

        val response     = oauthClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty token response")
        if (!response.isSuccessful) throw Exception("Token fetch failed: ${response.code}")

        val json      = JSONObject(responseBody)
        val token     = json.getString("access_token")
        val expiresIn = json.optLong("expires_in", 3600L)

        cachedToken = token
        tokenExpiry = now + (expiresIn * 1000L) - 60_000L  // expire 1 min early
        token
    }

    /** Fetch a page of posts from a subreddit via oauth.reddit.com (Bearer auth). */
    suspend fun fetch(
        subreddit: String,
        sort: String = "hot",
        after: String? = null,
    ): Pair<List<AdultItem>, String?> {
        val token = getAccessToken()
        val clean = subreddit.removePrefix("r/").trim()
        val url   = buildString {
            append("https://oauth.reddit.com/r/$clean/$sort")
            append("?limit=50&raw_json=1&include_over_18=on")
            if (after != null) append("&after=$after")
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .build()

            var response = oauthClient.newCall(request).execute()
            // Retry once on transient 5xx (HTTP/2 negotiation sometimes triggers a 500)
            if (response.code in 500..599) {
                response.body?.string()  // drain
                response = oauthClient.newCall(request).execute()
            }
            when (response.code) {
                401 -> {
                    cachedToken = null  // force refresh next time
                    throw RedditAuthRequiredException("r/$clean requires user verification.")
                }
                403 -> throw RedditAuthRequiredException("r/$clean is private or quarantined.")
                404 -> throw RedditAuthRequiredException("r/$clean was not found.")
                429 -> throw RedditRateLimitException("Reddit rate limit. Please wait a moment.")
                else -> if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(200).orEmpty()
                    throw Exception("Reddit API error ${response.code}: $errBody")
                }
            }

            val bodyStr  = response.body?.string() ?: throw Exception("Empty response")
            val listing  = redditJson.decodeFromString<RedditListing>(bodyStr)
            val children = listing.data?.children.orEmpty()
            val validData = children.mapNotNull { it.data }
            val items     = validData.mapNotNull { post ->
                try { post.toAdultItem() } catch (_: Exception) { null }
            }
            // Diagnostic: surface why 0 items came back so UI shows something useful
            if (items.isEmpty()) {
                val apiCount   = children.size
                val parsedData = validData.size
                if (apiCount == 0) {
                    // Show the first 300 chars of the raw body to diagnose quarantine/redirect responses
                    val bodyPreview = bodyStr.take(300).replace("\n", " ")
                    throw Exception("r/$clean: 0 posts. Body: $bodyPreview")
                }
                if (parsedData == 0) throw Exception("r/$clean: $apiCount posts received but none could be parsed (JSON structure mismatch)")
                throw Exception("r/$clean: $apiCount posts received, $parsedData parsed, 0 passed filter (all posts are text/link-only)")
            }
            items to listing.data?.after
        }
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
        "r/Asian_Hotties" to "Asian_Hotties",
        "r/latinas"      to "latinas",
        "r/ebony"        to "ebony",
        "r/cumsluts"     to "cumsluts",
        "r/nsfw_videos"  to "nsfw_videos",
    )
}
