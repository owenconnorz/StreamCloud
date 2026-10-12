package com.streamcloud.app.data.nuvio

internal data class NuvioWatchProgressDeleteKey(
    val tmdbId: Long,
    val mediaType: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val remoteContentId: String? = null,
    val remoteContentType: String? = null,
    val remoteVideoId: String? = null,
    val remoteProgressKey: String? = null,
) {
    val contentId: String
        get() = remoteContentId?.takeIf { it.isNotBlank() } ?: "tmdb:$tmdbId"
    val contentType: String
        get() = remoteContentType?.takeIf { it.isNotBlank() }
            ?: if (mediaType == "tv" || mediaType == "series") "series" else "movie"
    val remoteKey: String
        get() = remoteVideoId?.takeIf { it.isNotBlank() }
            ?: remoteProgressKey?.takeIf { it.isNotBlank() }
            ?: if (seasonNumber > 0 && episodeNumber > 0) {
                "$contentId:$seasonNumber:$episodeNumber"
            } else {
                contentId
            }

    fun serialize(): String {
        if (
            remoteContentId.isNullOrBlank() &&
            remoteContentType.isNullOrBlank() &&
            remoteVideoId.isNullOrBlank() &&
            remoteProgressKey.isNullOrBlank()
        ) {
            return listOf(tmdbId, mediaType, seasonNumber, episodeNumber).joinToString("|")
        }
        return listOf(
            "v2",
            tmdbId.toString(),
            mediaType,
            seasonNumber.toString(),
            episodeNumber.toString(),
            encodeDeleteKeyPart(remoteContentId),
            encodeDeleteKeyPart(remoteContentType),
            encodeDeleteKeyPart(remoteVideoId),
            encodeDeleteKeyPart(remoteProgressKey),
        ).joinToString("|")
    }

    companion object {
        fun deserialize(serialized: String): NuvioWatchProgressDeleteKey? {
            val parts = serialized.split('|')
            val keyParts = if (parts.firstOrNull() == "v2") {
                if (parts.size != 9) return null
                parts.drop(1)
            } else {
                if (parts.size != 4) return null
                parts
            }
            val tmdbId = keyParts[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val mediaType = keyParts[1].takeIf { it == "movie" || it == "tv" } ?: return null
            val seasonNumber = keyParts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val episodeNumber = keyParts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            if (parts.firstOrNull() != "v2") {
                return NuvioWatchProgressDeleteKey(
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                )
            }
            return NuvioWatchProgressDeleteKey(
                tmdbId = tmdbId,
                mediaType = mediaType,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                remoteContentId = decodeDeleteKeyPart(keyParts[4]),
                remoteContentType = decodeDeleteKeyPart(keyParts[5]),
                remoteVideoId = decodeDeleteKeyPart(keyParts[6]),
                remoteProgressKey = decodeDeleteKeyPart(keyParts[7]),
            )
        }
    }
}

internal fun NuvioWatchProgressDeleteKey.matchesLocalProgress(
    tmdbId: Long,
    mediaType: String,
    seasonNumber: Int,
    episodeNumber: Int,
): Boolean =
    this.tmdbId == tmdbId &&
        normalizedProgressMediaType(this.mediaType) == normalizedProgressMediaType(mediaType) &&
        this.seasonNumber == seasonNumber.coerceAtLeast(0) &&
        this.episodeNumber == episodeNumber.coerceAtLeast(0)

internal fun NuvioWatchProgressDeleteKey.matchesRemoteProgress(
    tmdbId: Long?,
    mediaType: String,
    seasonNumber: Int,
    episodeNumber: Int,
    remoteContentId: String,
): Boolean =
    (tmdbId != null && matchesLocalProgress(tmdbId, mediaType, seasonNumber, episodeNumber)) ||
        (
            !this.remoteContentId.isNullOrBlank() &&
                this.remoteContentId == remoteContentId &&
                normalizedProgressMediaType(this.mediaType) ==
                    normalizedProgressMediaType(mediaType) &&
                this.seasonNumber == seasonNumber.coerceAtLeast(0) &&
                this.episodeNumber == episodeNumber.coerceAtLeast(0)
            )

private fun normalizedProgressMediaType(mediaType: String): String =
    when (mediaType.trim().lowercase()) {
        "tv", "series", "episode" -> "tv"
        "movie" -> "movie"
        else -> mediaType.trim().lowercase()
    }

private fun encodeDeleteKeyPart(value: String?): String =
    java.net.URLEncoder.encode(value.orEmpty(), "UTF-8")

private fun decodeDeleteKeyPart(value: String): String? =
    runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

internal data class NuvioProgressTimes(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long?,
)

internal data class NuvioEpisodeNumbers(
    val seasonNumber: Int,
    val episodeNumber: Int,
)

internal fun nuvioEpisodeNumbers(
    season: Int?,
    seasonNumber: Int?,
    episode: Int?,
    episodeNumber: Int?,
    vararg identityKeys: String?,
): NuvioEpisodeNumbers {
    val directSeason = season?.takeIf { it > 0 } ?: seasonNumber?.takeIf { it > 0 }
    val directEpisode = episode?.takeIf { it > 0 } ?: episodeNumber?.takeIf { it > 0 }
    if (directSeason != null && directEpisode != null) {
        return NuvioEpisodeNumbers(directSeason, directEpisode)
    }

    val keyPattern = Regex(
        """(?i)^(?:tmdb:|imdb:)?(?:tt\d+|\d+)[:/](\d{1,2})[:/](\d{1,3})$""",
    )
    val encoded = identityKeys.asSequence()
        .filterNotNull()
        .map { it.trim() }
        .firstNotNullOfOrNull { key ->
            keyPattern.matchEntire(key)?.let { match ->
                val parsedSeason = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
                val parsedEpisode = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                if (parsedSeason != null && parsedEpisode != null) {
                    NuvioEpisodeNumbers(parsedSeason, parsedEpisode)
                } else {
                    null
                }
            }
        }

    return NuvioEpisodeNumbers(
        seasonNumber = directSeason ?: encoded?.seasonNumber ?: 0,
        episodeNumber = directEpisode ?: encoded?.episodeNumber ?: 0,
    )
}

internal fun normalizeNuvioProgressTimes(
    position: Long,
    duration: Long,
    lastWatched: Long,
): NuvioProgressTimes {
    val durationSecondsBased = duration in 1 until 60_000L
    fun multiplySeconds(value: Long): Long =
        if (value > 0L && value > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE
        else value * 1_000L
    val durationMs = if (durationSecondsBased) multiplySeconds(duration) else duration
    val positionMs = when {
        position <= 0L -> position
        durationSecondsBased && position <= duration -> multiplySeconds(position)
        !durationSecondsBased &&
            duration > 0L &&
            position < 60_000L &&
            position <= duration / 1_000L -> multiplySeconds(position)
        else -> position
    }
    val timestampMs = when {
        lastWatched <= 0L -> null
        lastWatched < 100_000_000_000L -> toEpochMillis(lastWatched)
        else -> lastWatched
    }
    return NuvioProgressTimes(
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAtMs = timestampMs,
    )
}

internal fun shouldUseNuvioProgress(
    existingUpdatedAt: Long?,
    existingDurationMs: Long?,
    incomingUpdatedAtMs: Long,
    incomingRawDuration: Long,
    incomingDurationMs: Long,
): Boolean =
    existingUpdatedAt == null ||
        existingUpdatedAt < incomingUpdatedAtMs ||
        (incomingDurationMs != incomingRawDuration && existingDurationMs == incomingRawDuration)

private fun toEpochMillis(seconds: Long): Long =
    if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L