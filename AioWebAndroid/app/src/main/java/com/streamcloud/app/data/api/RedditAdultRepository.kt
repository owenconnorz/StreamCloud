package com.streamcloud.app.data.api

import com.streamcloud.app.data.network.Net
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Source-agnostic adult item used by the UI grid. Mirrors what
 * `EpornerVideo` exposes but adds optional fields needed for Reddit
 * (audio track URL, gallery flag, source label).
 */
data class AdultItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val previewImage: String?,
    val durationLabel: String?,
    /** Direct streamable URL when known. For Eporner this is empty until resolution. */
    val streamUrl: String?,
    /** Reddit dashes audio separately at DASH_AUDIO_*.mp4. Null for non-Reddit / no audio. */
    val audioUrl: String? = null,
    val isVideo: Boolean = true,
    val isGallery: Boolean = false,
    val source: AdultSource,
    /** Eporner page id — needed to resolve direct MP4 lazily. Null for Reddit. */
    val epornerId: String? = null,
    val embedUrl: String? = null,
)

enum class AdultSource(val label: String) {
    Eporner("Eporner"),
    Reddit("Reddit"),
}

object RedditAdultRepository {
    private val api: RedditApi by lazy {
        Net.retrofit("https://www.reddit.com/").create(RedditApi::class.java)
    }

    /**
     * Fetch one page of media-only posts from the given subreddit.
     * Returns the items + the next-page `after` cursor for endless scroll.
     */
    suspend fun fetch(
        subreddit: String,
        sort: String = "hot",
        after: String? = null,
    ): Pair<List<AdultItem>, String?> {
        val clean = subreddit.removePrefix("r/").trim()
        val resp = api.listing(subreddit = clean, sort = sort, after = after)
        val children = resp.data?.children.orEmpty()
        val items = children.mapNotNull { it.data?.toAdultItem() }
        return items to resp.data?.after
    }

    private fun RedditPost.toAdultItem(): AdultItem? {
        // ----- video URL resolution (Reddit-hosted, redgifs, direct .mp4/.webm) -----
        val redditVideo = media?.dashVideo() ?: secure_media?.dashVideo()
        val previewVideo = (preview?.get("reddit_video_preview") as? JsonObject)?.fallbackUrl()

        var streamUrl: String? = null
        var audioUrl: String? = null
        var isVideo = false

        if (redditVideo != null) {
            streamUrl = redditVideo.fallbackUrl?.removeSuffix("?source=fallback")
            // DASH audio sibling — usually at DASH_AUDIO_128.mp4 alongside the video.
            val base = streamUrl
                ?.replace(Regex("DASH_\\d+\\.mp4.*$"), "")
                ?.replace(Regex("DASH_[^/]+\\.mp4.*$"), "")
            if (!base.isNullOrBlank() && redditVideo.hasAudio != false) {
                audioUrl = "${base}DASH_AUDIO_128.mp4"
            }
            isVideo = true
        } else if (previewVideo != null) {
            streamUrl = previewVideo.removeSuffix("?source=fallback")
            isVideo = true
        } else if (url.contains("redgifs.com", ignoreCase = true)) {
            // Reddit doesn't always inline redgifs media — fall back to the watch page
            // URL; the player can hand off to a WebView for those edge cases.
            val match = Regex("redgifs\\.com/(?:watch/)?([\\w-]+)", RegexOption.IGNORE_CASE).find(url)
            streamUrl = if (match != null) "https://www.redgifs.com/ifr/${match.groupValues[1]}" else url
            isVideo = true
        } else if (url.endsWith(".mp4", true) || url.endsWith(".webm", true)) {
            streamUrl = url
            isVideo = true
        } else if (url.endsWith(".gifv", true)) {
            streamUrl = url.replaceFirst(".gifv", ".mp4", ignoreCase = true)
            isVideo = true
        }

        // ----- thumbnail / preview image -----
        val thumb = listOfNotNull(
            preview?.previewImageSource(),
            thumbnail?.takeIf { it != "self" && it != "default" && it != "nsfw" && it.startsWith("http") },
            url.takeIf { it.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE)) },
        ).firstOrNull()

        val isGallery = is_gallery
        // Drop posts we can't actually render at all (text posts, deleted, external links, etc).
        if (!isVideo && thumb == null && !isGallery) return null

        return AdultItem(
            id = id,
            title = title.ifBlank { "r/$subreddit" },
            thumbnail = thumb,
            previewImage = thumb,
            durationLabel = null,
            streamUrl = streamUrl,
            audioUrl = audioUrl,
            isVideo = isVideo,
            isGallery = isGallery,
            source = AdultSource.Reddit,
        )
    }

    // ---------------------- JSON-walker helpers ---------------------------------
    private data class DashVideo(val fallbackUrl: String?, val hlsUrl: String?, val hasAudio: Boolean?)

    private fun JsonObject.dashVideo(): DashVideo? {
        val v = (this["reddit_video"] as? JsonObject) ?: return null
        return DashVideo(
            fallbackUrl = (v["fallback_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            hlsUrl = (v["hls_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            hasAudio = (v["has_audio"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull,
        )
    }

    private fun JsonObject.fallbackUrl(): String? =
        (this["fallback_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    /** Pulls the largest preview image url out of `preview.images[0].source.url`. */
    private fun JsonObject.previewImageSource(): String? = runCatching {
        val images = (this["images"] as? kotlinx.serialization.json.JsonArray) ?: return@runCatching null
        val first = images.firstOrNull()?.jsonObject ?: return@runCatching null
        val source = (first["source"] as? JsonObject) ?: return@runCatching null
        (source["url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.getOrNull()
}

/** Cosmetic preset chips — same labels the StreamCloud web app shipped with. */
object RedditAdultSubs {
    val PRESETS: List<Pair<String, String>> = listOf(
        "r/nsfw" to "nsfw",
        "r/gonewild" to "gonewild",
        "r/RealGirls" to "RealGirls",
        "r/Amateur" to "amateur",
        "r/NSFW_GIF" to "nsfw_gif",
        "r/porn" to "porn",
        "r/LegalTeens" to "LegalTeens",
        "r/collegesluts" to "collegesluts",
        "r/Boobies" to "Boobies",
        "r/ass" to "ass",
        "r/pawg" to "pawg",
        "r/thick" to "thick",
        "r/milf" to "milf",
        "r/Asian_Hotties" to "Asian_Hotties",
        "r/latinas" to "latinas",
        "r/ebony" to "ebony",
        "r/cumsluts" to "cumsluts",
        "r/nsfw_videos" to "nsfw_videos",
    )
}
