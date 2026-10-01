package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.data.library.WatchProgressEntity

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
