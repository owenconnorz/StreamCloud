package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.library.WatchProgressEntity

internal data class ContinueWatchingResumeTarget(
    val mediaType: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

internal fun continueWatchingResumeTarget(
    entry: WatchProgressEntity,
): ContinueWatchingResumeTarget {
    val routeParts = entry.sourceRoute
        ?.takeIf { it.startsWith("sources:", ignoreCase = true) }
        ?.split("|||")
        .orEmpty()
    val titleEpisode = Regex(
        """\bS(\d{1,2})\s*E(\d{1,3})\b""",
        RegexOption.IGNORE_CASE,
    ).find(entry.title)
    val season = entry.seasonNumber.takeIf { it > 0 }
        ?: routeParts.getOrNull(1)?.toIntOrNull()
        ?: titleEpisode?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = entry.episodeNumber.takeIf { it > 0 }
        ?: routeParts.getOrNull(2)?.toIntOrNull()
        ?: titleEpisode?.groupValues?.getOrNull(2)?.toIntOrNull()
    val mediaType = routeParts.firstOrNull()
        ?.removePrefix("sources:")
        ?.takeIf { it.equals("movie", ignoreCase = true) || it.equals("tv", ignoreCase = true) }
        ?.lowercase()
        ?: entry.mediaType.lowercase()

    return ContinueWatchingResumeTarget(
        mediaType = mediaType,
        seasonNumber = season?.takeIf { it > 0 },
        episodeNumber = episode?.takeIf { it > 0 },
    )
}

/**
 * Keep one Continue Watching card per title. The DAO orders newest progress first, and these
 * tie-breakers match latestInProgressEpisode so Home and the title page choose the same episode.
 */
internal fun latestContinueWatchingByTitle(
    rows: List<WatchProgressEntity>,
): List<WatchProgressEntity> = rows
    .sortedWith(
        compareByDescending<WatchProgressEntity> { it.updatedAt }
            .thenByDescending { it.seasonNumber }
            .thenByDescending { it.episodeNumber },
    )
    .distinctBy { it.tmdbId to it.mediaType }
