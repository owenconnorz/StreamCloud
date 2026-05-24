@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile

// ── ExtractorLinkType ──────────────────────────────────────────────────────────
// Defined here (utils package) so that plugins compiled against the real
// CloudStream SDK find com.lagradost.cloudstream3.utils.ExtractorLinkType.

enum class ExtractorLinkType {
    /** Single stream (mp4, webm, etc.) */
    VIDEO,
    /** HLS / m3u8 playlist */
    M3U8,
    /** DASH / mpd manifest */
    DASH,
    /** BitTorrent info-hash or .torrent file URL */
    TORRENT,
    /** Magnet link */
    MAGNET;

    companion object {
        fun fromM3u8(isM3u8: Boolean) = if (isM3u8) M3U8 else VIDEO
    }
}

// ── Qualities ──────────────────────────────────────────────────────────────────
// Real CloudStream defines this as an object with integer constants in utils.
// Plugins call e.g. Qualities.P1080 (int) not Qualities.P1080.value (enum).

object Qualities {
    const val Unknown = -2
    const val P144 = 144
    const val P240 = 240
    const val P360 = 360
    const val P480 = 480
    const val P720 = 720
    const val P1080 = 1080
    const val P1440 = 1440
    const val P2160 = 2160

    fun getStringByInt(q: Int?): String = "${(q ?: 0).takeIf { it > 0 } ?: "?"}p"

    /** Fuzzy name → quality int */
    fun getQualityFromName(name: String?): Int = when (name?.lowercase()?.trim()) {
        null, "" -> Unknown
        "144p", "144" -> P144; "240p", "240" -> P240
        "360p", "360" -> P360; "480p", "480" -> P480
        "720p", "720", "hd" -> P720; "1080p", "1080", "fhd" -> P1080
        "1440p", "1440" -> P1440; "2160p", "2160", "4k", "uhd" -> P2160
        else -> name.filter(Char::isDigit).toIntOrNull() ?: Unknown
    }
}

// ── ExtractorLink ──────────────────────────────────────────────────────────────
// Core data class — MUST live in com.lagradost.cloudstream3.utils because
// compiled plugins reference com.lagradost.cloudstream3.utils.ExtractorLink.
// Both legacy (isM3u8: Boolean) and modern (type: ExtractorLinkType) constructor
// patterns are supported for maximum plugin compatibility.

open class ExtractorLink(
    open val source: String,
    open val name: String,
    open val url: String,
    open var referer: String = "",
    open var quality: Int = Qualities.Unknown,
    /** Legacy boolean for plugins compiled against older CloudStream SDK. */
    open var isM3u8: Boolean = false,
    open var headers: Map<String, String> = emptyMap(),
    open var extractorData: String? = null,
    open var type: ExtractorLinkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
    open var audioTracks: List<Any> = emptyList(),
) {
    override fun toString(): String = "ExtractorLink($source · $name · ${quality}p)"
}

// ── Global extractor registry ──────────────────────────────────────────────────

val extractorApis: MutableList<ExtractorApi> = mutableListOf()

/** Invoke all registered extractors whose mainUrl matches [url]. */
suspend fun invokeExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit = {},
    callback: (ExtractorLink) -> Unit = {},
) {
    extractorApis.forEach { api ->
        if (url.startsWith(api.mainUrl)) {
            runCatching { api.getSafeUrl(url, referer, subtitleCallback, callback) }
        }
    }
}

// ── ExtractorApi ───────────────────────────────────────────────────────────────

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean

    open var sourcePlugin: String? = null

    @Throws(Exception::class)
    abstract suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>?

    open suspend fun getSafeUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit = {},
    ) {
        getUrl(url, referer)?.forEach(callback)
    }

    open fun getExtractorUrl(id: String): String = id

    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        getUrl(url, referer)?.forEach(callback)
        return true
    }
}

// ── ExtractorApiKt top-level helpers ───────────────────────────────────────────
// Generates com.lagradost.cloudstream3.utils.ExtractorApiKt class expected by plugins.

object ShortLink {
    suspend fun resolve(url: String): String = url
}

object AppUtils {
    /** Serialize any object to its JSON representation.
     *  If the receiver is already a String it is returned as-is.
     *  Mirrors com.lagradost.cloudstream3.utils.AppUtils.toJson from the real
     *  CloudStream library — plugins call AppUtils.INSTANCE.toJson(obj). */
    fun Any.toJson(): String {
        if (this is String) return this
        return com.lagradost.cloudstream3.mapper.writeValueAsString(this)
    }

    /** Deserialize a JSON string to T using the shared Jackson mapper. */
    inline fun <reified T> parseJson(value: String): T {
        return com.lagradost.cloudstream3.mapper.readValue(value, T::class.java)
    }

    /** Like parseJson but returns null instead of throwing on any failure. */
    inline fun <reified T> tryParseJson(value: String?): T? {
        return try {
            parseJson(value ?: return null)
        } catch (_: Exception) {
            null
        }
    }
}
