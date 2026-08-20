package com.streamcloud.app.data.api

import com.streamcloud.app.data.network.BrowserHeaders
import com.streamcloud.app.data.network.Net
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

data class EpornerResolvedPlayback(
    val url: String,
    val headers: Map<String, String>,
)

@Serializable
internal data class EpornerResolverSource(
    val src: String? = null,
    val height: Int? = null,
)

@Serializable
internal data class EpornerResolverAdaptiveSource(
    val auto: EpornerResolverSource? = null,
)

@Serializable
internal data class EpornerResolverSources(
    val hls: EpornerResolverAdaptiveSource? = null,
    val mp4: Map<String, EpornerResolverSource> = emptyMap(),
)

@Serializable
internal data class EpornerResolverResponse(
    val sources: EpornerResolverSources? = null,
)

internal data class EpornerResolverEmbedConfig(
    val videoId: String,
    val encodedHash: String,
)

internal fun parseEpornerResolverEmbedConfig(page: String): EpornerResolverEmbedConfig? {
    val videoId = Regex("""EP\.video\.player\.vid\s*=\s*['"]([^'"]+)""")
        .find(page)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: return null
    val rawHash = Regex("""EP\.video\.player\.hash\s*=\s*['"]([0-9a-fA-F]{32})""")
        .find(page)?.groupValues?.getOrNull(1)
        ?: return null
    val encodedHash = rawHash.chunked(8)
        .joinToString(separator = "") { chunk -> chunk.toLong(16).toString(36) }
    return EpornerResolverEmbedConfig(videoId, encodedHash)
}

internal fun EpornerResolverResponse.bestEpornerResolverSource(): EpornerResolverSource? {
    val playbackSources = sources ?: return null
    playbackSources.hls?.auto?.takeIf { !it.src.isNullOrBlank() }?.let { return it }
    return playbackSources.mp4
        .entries
        .sortedByDescending { (quality, source) ->
            source.height ?: quality.filter(Char::isDigit).toIntOrNull() ?: 0
        }
        .firstNotNullOfOrNull { (_, source) -> source.takeIf { !it.src.isNullOrBlank() } }
}

private interface EpornerPlaybackEndpoint {
    @GET
    suspend fun embedPage(@Url url: String): ResponseBody

    @GET("xhr/video/{videoId}")
    suspend fun playbackSources(
        @Path("videoId") videoId: String,
        @Query("hash") hash: String,
        @Query("domain") domain: String = "www.eporner.com",
        @Query("pixelRatio") pixelRatio: Int = 1,
        @Query("playerWidth") playerWidth: Int = 960,
        @Query("playerHeight") playerHeight: Int = 540,
        @Query("fallback") fallback: Boolean = false,
        @Query("embed") embed: Boolean = true,
        @Query("supportedFormats") supportedFormats: String = "hls,mp4",
        @Query("_") requestTimestamp: Long = System.currentTimeMillis(),
    ): EpornerResolverResponse
}

object EpornerPlaybackResolver {
    private val endpoint: EpornerPlaybackEndpoint by lazy {
        Net.retrofit("https://www.eporner.com/").create(EpornerPlaybackEndpoint::class.java)
    }

    suspend fun resolve(videoId: String, fallbackEmbed: String): EpornerResolvedPlayback {
        val normalizedEmbed = when {
            fallbackEmbed.startsWith("//") -> "https:$fallbackEmbed"
            fallbackEmbed.startsWith("/") -> "https://www.eporner.com$fallbackEmbed"
            else -> fallbackEmbed
        }
        if (videoId.startsWith("direct://")) {
            return EpornerResolvedPlayback(
                url = videoId.removePrefix("direct://").ifBlank { normalizedEmbed },
                headers = emptyMap(),
            )
        }
        require(normalizedEmbed.startsWith("https://")) {
            "Eporner did not provide a valid playback page."
        }
        val config = parseEpornerResolverEmbedConfig(endpoint.embedPage(normalizedEmbed).string())
            ?: error("Eporner did not provide a playable video configuration.")
        val source = endpoint.playbackSources(
            videoId = config.videoId,
            hash = config.encodedHash,
        ).bestEpornerResolverSource()
            ?: error("Eporner has no stream available for this video.")
        val url = source.src?.takeIf { it.isNotBlank() }
            ?: error("Eporner returned an empty stream URL.")
        return EpornerResolvedPlayback(url, PLAYBACK_HEADERS)
    }

    private val PLAYBACK_HEADERS = mapOf(
        "User-Agent" to BrowserHeaders.USER_AGENT,
        "Referer" to "https://www.eporner.com/",
        "Origin" to "https://www.eporner.com",
        "Accept" to "*/*",
    )
}