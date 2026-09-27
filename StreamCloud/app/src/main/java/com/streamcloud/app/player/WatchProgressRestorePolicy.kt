package com.streamcloud.app.player

import com.streamcloud.app.data.library.WatchProgressEntity

internal fun restorableWatchPosition(
    key: WatchProgressKey,
    savedProgress: WatchProgressEntity?,
): Long? {
    if (savedProgress == null) return null
    if (savedProgress.tmdbId != key.tmdbId) return null
    if (savedProgress.title != key.title || savedProgress.mediaType != key.mediaType) return null
    return savedProgress.positionMs.takeIf {
        canRestoreWatchPosition(it, savedProgress.durationMs)
    }
}

internal fun canRestoreWatchPosition(positionMs: Long, durationMs: Long): Boolean =
    positionMs > 5_000L &&
        durationMs > 0L &&
        positionMs < durationMs &&
        positionMs.toDouble() / durationMs.toDouble() < 0.95

internal fun progressKeyForBingeEpisode(episode: BingeEpisode): WatchProgressKey {
    val episodeIdentity = "S${episode.seasonNumber}E${episode.episodeNumber}"
    val title = buildString {
        append(episode.title)
        if (!episode.title.contains(episodeIdentity, ignoreCase = true)) {
            append(' ')
            append(episodeIdentity)
        }
        episode.episodeTitle
            ?.takeIf { it.isNotBlank() && !episode.title.contains(it, ignoreCase = true) }
            ?.let {
                append(" · ")
                append(it)
            }
    }
    val base = episode.progressKey
    return if (base != null) {
        base.copy(
            tmdbId = episode.tmdbId,
            title = title,
            posterUrl = episode.posterUrl ?: base.posterUrl,
            mediaType = "tv",
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            showTitle = base.showTitle ?: episode.title,
            episodeTitle = base.episodeTitle ?: episode.episodeTitle,
            sourceRoute = base.sourceRoute?.takeIf {
                it.isNotBlank() && !it.startsWith("sources:")
            }
                ?: sourceSelectionRoute("tv", episode.seasonNumber, episode.episodeNumber),
        )
    } else {
        WatchProgressKey(
            tmdbId = episode.tmdbId,
            title = title,
            posterUrl = episode.posterUrl,
            mediaType = "tv",
            sourceRoute = sourceSelectionRoute("tv", episode.seasonNumber, episode.episodeNumber),
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            showTitle = episode.title,
            episodeTitle = episode.episodeTitle,
        )
    }
}
