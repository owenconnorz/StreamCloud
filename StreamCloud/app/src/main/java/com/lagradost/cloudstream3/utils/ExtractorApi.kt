@file:Suppress("unused", "MemberVisibilityCanBePrivate", "DEPRECATION_ERROR", "DEPRECATION")
package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.annotation.JsonIgnore
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.IDownloadableMinimum
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// ---------------------------------------------------------------------------
// ExtractorLinkType
// ---------------------------------------------------------------------------

enum class ExtractorLinkType {
    VIDEO, M3U8, DASH, TORRENT, MAGNET;

    fun getMimeType(): String = when (this) {
        VIDEO   -> "video/mp4"
        M3U8    -> "application/x-mpegURL"
        DASH    -> "application/dash+xml"
        TORRENT -> "application/x-bittorrent"
        MAGNET  -> "application/x-bittorrent"
    }
}

private fun inferTypeFromUrl(url: String): ExtractorLinkType {
    val path = try { URI(url).path } catch (_: Throwable) { null }
    return when {
        path?.endsWith(".m3u8") == true    -> ExtractorLinkType.M3U8
        path?.endsWith(".mpd")  == true    -> ExtractorLinkType.DASH
        path?.endsWith(".torrent") == true -> ExtractorLinkType.TORRENT
        url.startsWith("magnet:")          -> ExtractorLinkType.MAGNET
        else                               -> ExtractorLinkType.VIDEO
    }
}

val INFER_TYPE: ExtractorLinkType? = null

// ---------------------------------------------------------------------------
// DRM UUIDs
// ---------------------------------------------------------------------------

val CLEARKEY_UUID  = UUID(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL)
val WIDEVINE_UUID  = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
val PLAYREADY_UUID = UUID(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)

// ---------------------------------------------------------------------------
// Qualities
// ---------------------------------------------------------------------------

enum class Qualities(var value: Int, val defaultPriority: Int) {
    Unknown(400, 4),
    P144(144, 0),
    P240(240, 2),
    P360(360, 3),
    P480(480, 4),
    P720(720, 5),
    P1080(1080, 6),
    P1440(1440, 7),
    P2160(2160, 8);

    companion object {
        fun getStringByInt(qual: Int?): String = when (qual) {
            0 -> "Auto"; Unknown.value -> ""; P2160.value -> "4K"; null -> ""
            else -> "${qual}p"
        }
        fun getStringByIntFull(quality: Int): String = when (quality) {
            0 -> "Auto"; Unknown.value -> "Unknown"; P2160.value -> "4K"
            else -> "${quality}p"
        }
    }
}

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return Qualities.Unknown.value
    val match = qualityName.lowercase().replace("p", "").trim()
    return when (match) { "4k" -> Qualities.P2160 else -> null }?.value
        ?: match.toIntOrNull() ?: Qualities.Unknown.value
}

// ---------------------------------------------------------------------------
// schemaStripRegex
// ---------------------------------------------------------------------------

val schemaStripRegex = Regex("""^(https:|)//(www\.|)""")

// ---------------------------------------------------------------------------
// PlayListItem / ExtractorLinkPlayList
// ---------------------------------------------------------------------------

data class PlayListItem(val url: String, val durationUs: Long)

fun Long.toUs(): Long = this * 1_000_000

@Suppress("DEPRECATION")
data class ExtractorLinkPlayList(
    override val source: String,
    override val name: String,
    val playlist: List<PlayListItem>,
    override var referer: String,
    override var quality: Int,
    override var headers: Map<String, String> = mapOf(),
    override var extractorData: String? = null,
    override var type: ExtractorLinkType,
    override var audioTracks: List<AudioFile> = emptyList(),
) : ExtractorLink(
    source = source, name = name, url = "",
    referer = referer, quality = quality,
    headers = headers, extractorData = extractorData,
    type = type, audioTracks = audioTracks
) {
    constructor(
        source: String, name: String, playlist: List<PlayListItem>,
        referer: String, quality: Int, isM3u8: Boolean = false,
        headers: Map<String, String> = mapOf(), extractorData: String? = null,
    ) : this(
        source = source, name = name, playlist = playlist, referer = referer, quality = quality,
        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        headers = headers, extractorData = extractorData,
    )
}

// ---------------------------------------------------------------------------
// ExtractorLink
// ---------------------------------------------------------------------------

open class ExtractorLink
@Deprecated("Use newExtractorLink", level = DeprecationLevel.WARNING)
constructor(
    open val source: String,
    open val name: String,
    override val url: String,
    override var referer: String,
    open var quality: Int,
    override var headers: Map<String, String> = mapOf(),
    open var extractorData: String? = null,
    open var type: ExtractorLinkType,
    open var audioTracks: List<AudioFile> = emptyList(),
) : IDownloadableMinimum {

    val isM3u8: Boolean get() = type == ExtractorLinkType.M3U8
    val isDash: Boolean get() = type == ExtractorLinkType.DASH

    @JsonIgnore
    fun getAllHeaders(): Map<String, String> {
        if (referer.isBlank()) return headers
        if (headers.keys.none { it.equals("referer", ignoreCase = true) })
            return headers + mapOf("referer" to referer)
        return headers
    }

    suspend fun getVideoSize(timeoutSeconds: Long = 3L): Long? {
        if (type != ExtractorLinkType.VIDEO) return null
        return try {
            app.head(url, headers = headers, referer = referer, timeout = timeoutSeconds.toInt())
                .headers["Content-Length"]?.firstOrNull()?.toLong()
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String,
        referer: String? = null, quality: Int? = null,
        type: ExtractorLinkType? = INFER_TYPE,
        headers: Map<String, String> = mapOf(),
        extractorData: String? = null,
    ) : this(
        source = source, name = name, url = url,
        referer = referer ?: "", quality = quality ?: Qualities.Unknown.value,
        headers = headers, extractorData = extractorData,
        type = type ?: inferTypeFromUrl(url)
    )

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String,
        referer: String, quality: Int,
        type: ExtractorLinkType?,
        headers: Map<String, String> = mapOf(),
        extractorData: String? = null,
    ) : this(
        source = source, name = name, url = url,
        referer = referer, quality = quality,
        headers = headers, extractorData = extractorData,
        type = type ?: inferTypeFromUrl(url)
    )

    @Suppress("DEPRECATION_ERROR", "DEPRECATION")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String,
        referer: String, quality: Int,
        isM3u8: Boolean = false,
        headers: Map<String, String> = mapOf(),
        extractorData: String? = null,
    ) : this(source, name, url, referer, quality, isM3u8, headers, extractorData, false)

    @Suppress("DEPRECATION")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String,
        referer: String, quality: Int,
        isM3u8: Boolean = false,
        headers: Map<String, String> = mapOf(),
        extractorData: String? = null,
        isDash: Boolean,
    ) : this(
        source = source, name = name, url = url,
        referer = referer, quality = quality,
        headers = headers, extractorData = extractorData,
        type = if (isDash) ExtractorLinkType.DASH else if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
    )

    override fun toString(): String = "ExtractorLink(name=$name, url=$url, referer=$referer, type=$type)"
}

// ---------------------------------------------------------------------------
// newExtractorLink
// ---------------------------------------------------------------------------

suspend fun newExtractorLink(
    source: String, name: String, url: String,
    type: ExtractorLinkType? = null,
    initializer: suspend ExtractorLink.() -> Unit = {}
): ExtractorLink {
    @Suppress("DEPRECATION_ERROR")
    val builder = ExtractorLink(
        source = source, name = name, url = url,
        type = type ?: INFER_TYPE
    )
    builder.initializer()
    return builder
}

// ---------------------------------------------------------------------------
// DrmExtractorLink
// ---------------------------------------------------------------------------

@Suppress("DEPRECATION")
open class DrmExtractorLink private constructor(
    override val source: String,
    override val name: String,
    override val url: String,
    override var referer: String,
    override var quality: Int,
    override var headers: Map<String, String> = mapOf(),
    override var extractorData: String? = null,
    override var type: ExtractorLinkType,
    open var kid: String? = null,
    open var key: String? = null,
    open var uuid: UUID,
    open var kty: String? = null,
    open var keyRequestParameters: HashMap<String, String>,
    open var licenseUrl: String? = null,
    override var audioTracks: List<AudioFile> = emptyList(),
) : ExtractorLink(source, name, url, referer, quality, headers, extractorData, type, audioTracks) {

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newDrmExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String,
        referer: String? = null, quality: Int? = null,
        type: ExtractorLinkType? = INFER_TYPE,
        headers: Map<String, String> = mapOf(),
        extractorData: String? = null,
        kid: String? = null, key: String? = null,
        uuid: UUID = CLEARKEY_UUID, kty: String? = "oct",
        keyRequestParameters: HashMap<String, String> = hashMapOf(),
        licenseUrl: String? = null,
    ) : this(
        source = source, name = name, url = url,
        referer = referer ?: "", quality = quality ?: Qualities.Unknown.value,
        headers = headers, extractorData = extractorData,
        type = type ?: inferTypeFromUrl(url),
        kid = kid, key = key, uuid = uuid,
        keyRequestParameters = keyRequestParameters, kty = kty,
        licenseUrl = licenseUrl,
    )
}

suspend fun newDrmExtractorLink(
    source: String, name: String, url: String,
    type: ExtractorLinkType? = null, uuid: UUID,
    initializer: suspend DrmExtractorLink.() -> Unit = {}
): DrmExtractorLink {
    @Suppress("DEPRECATION_ERROR")
    val builder = DrmExtractorLink(
        source = source, name = name, url = url, uuid = uuid, type = type ?: INFER_TYPE
    )
    builder.initializer()
    return builder
}

// ---------------------------------------------------------------------------
// AbstractExtractorApi
// ---------------------------------------------------------------------------

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean
    var sourcePlugin: String? = null

    @Throws
    open suspend fun getUrl(
        url: String, referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) { getUrl(url, referer)?.forEach(callback) }

    suspend fun getSafeUrl(
        url: String, referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) { try { getUrl(url, referer, subtitleCallback, callback) } catch (e: Exception) { logError(e) } }

    @Throws
    open suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>? = emptyList()

    open fun getExtractorUrl(id: String): String = id
}

fun ExtractorApi.fixUrl(url: String): String {
    if (url.startsWith("http") || url.startsWith("{\"")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    return if (url.startsWith('/')) mainUrl + url else "$mainUrl/$url"
}

// ---------------------------------------------------------------------------
// loadExtractor + extractorApis registry
// ---------------------------------------------------------------------------

val extractorApis: MutableList<ExtractorApi> = arrayListOf()

fun getExtractorApiFromName(name: String): ExtractorApi? =
    extractorApis.firstOrNull { it.name == name }

fun requireReferer(name: String): Boolean =
    extractorApis.firstOrNull { it.name == name }?.requiresReferer ?: false

fun httpsify(url: String): String = if (url.startsWith("//")) "https:$url" else url

suspend fun loadExtractor(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = loadExtractor(url = url, referer = null, subtitleCallback = subtitleCallback, callback = callback)

@Throws(CancellationException::class)
suspend fun loadExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    coroutineScope { ensureActive() }
    val compareUrl = url.lowercase().replace(schemaStripRegex, "")
    for (index in extractorApis.lastIndex downTo 0) {
        val extractor = extractorApis[index]
        if (compareUrl.startsWith(extractor.mainUrl.replace(schemaStripRegex, ""))) {
            try { extractor.getUrl(url, referer, subtitleCallback, callback) }
            catch (e: Exception) { logError(e); if (e is CancellationException) throw e }
            return true
        }
    }
    return false
}

// ---------------------------------------------------------------------------
// Misc helpers
// ---------------------------------------------------------------------------

private val packedRegex = Regex("""eval\(function\(p,a,c,k,e,.*\)\)""")
fun getPacked(string: String): String? = packedRegex.find(string)?.value

fun getAndUnpack(string: String): String {
    val packed = getPacked(string) ?: return string
    return try { JsUnpacker(packed).unpack() ?: string } catch (_: Exception) { string }
}

suspend fun unshortenLinkSafe(url: String): String = url  // stub — no ShortLink in stubs
