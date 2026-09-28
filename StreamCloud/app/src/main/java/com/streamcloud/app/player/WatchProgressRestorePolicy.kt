package com.streamcloud.app.player

import com.streamcloud.app.data.library.WatchProgressEntity

internal const val WATCH_COMPLETION_THRESHOLD = 0.95

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
        positionMs.toDouble() / durationMs.toDouble() < WATCH_COMPLETION_THRESHOLD

internal sealed class CompletedWatchTarget(open val tmdbId: Long) {
    data class Movie(
        override val tmdbId: Long,
        val title: String,
        val posterUrl: String?,
    ) : CompletedWatchTarget(tmdbId)

    data class Episode(
        override val tmdbId: Long,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val showTitle: String,
        val episodeTitle: String?,
    ) : CompletedWatchTarget(tmdbId)
}

internal fun completedWatchTarget(
    key: WatchProgressKey?,
    fallbackSeasonNumber: Int? = null,
    fallbackEpisodeNumber: Int? = null,
): CompletedWatchTarget? {
    key ?: return null
    if (key.tmdbId <= 0L) return null

    return when (key.mediaType.trim().lowercase()) {
        "movie" -> CompletedWatchTarget.Movie(
            tmdbId = key.tmdbId,
            title = key.title,
            posterUrl = key.posterUrl,
        )

        "tv" -> {
            val season = key.seasonNumber ?: fallbackSeasonNumber ?: return null
            val episode = key.episodeNumber ?: fallbackEpisodeNumber ?: return null
            if (season < 0 || episode <= 0) return null

            val fallbackTitle = key.title
                .substringBefore(" · ")
                .replace(Regex("""\s+S\d{1,2}E\d{1,3}$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { key.title }
            CompletedWatchTarget.Episode(
                tmdbId = key.tmdbId,
                seasonNumber = season,
                episodeNumber = episode,
                showTitle = key.showTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                episodeTitle = key.episodeTitle,
            )
        }

        else -> null
    }
}

internal fun playbackReachedWatchCompletion(
    positionMs: Long,
    durationMs: Long,
    playbackEnded: Boolean,
): Boolean = playbackEnded ||
    (durationMs > 0L &&
        positionMs >= 0L &&
        positionMs.toDouble() / durationMs.toDouble() >= WATCH_COMPLETION_THRESHOLD)

internal fun progressKeyForBingeEpisode(episode: BingeEpisode): WatchProgressKey {
    episode.progressKey?.let { return it }

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
    return WatchProgressKey(
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
