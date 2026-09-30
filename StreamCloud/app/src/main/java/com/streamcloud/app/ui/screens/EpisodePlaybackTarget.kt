package com.streamcloud.app.ui.screens

import com.streamcloud.app.data.api.TmdbTvSeasonSummary
import com.streamcloud.app.data.library.WatchedEpisodeEntity
import com.streamcloud.app.data.library.WatchProgressEntity

internal data class EpisodePlaybackTarget(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val isContinue: Boolean,
    val episodeTitle: String? = null,
) {
    val buttonLabel: String
        get() = "${if (isContinue) "Continue" else "Play"} S${seasonNumber}E${episodeNumber}"
}

internal fun chooseEpisodePlaybackTarget(
    inProgressEpisode: WatchProgressEntity?,
    watchedEpisodes: List<WatchedEpisodeEntity>,
    seasons: List<TmdbTvSeasonSummary>,
    selectedSeason: Int?,
    seasonSelectedByUser: Boolean,
): EpisodePlaybackTarget? {
    val availableSeasons = seasons
        .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
        .sortedBy { it.seasonNumber }
    if (availableSeasons.isEmpty()) return null

    val watchedKeys = watchedEpisodes
        .filter { it.seasonNumber > 0 && it.episodeNumber > 0 }
        .mapTo(mutableSetOf<Pair<Int, Int>>()) { it.seasonNumber to it.episodeNumber }

    inProgressEpisode
        ?.takeIf {
            it.mediaType.equals("tv", ignoreCase = true) &&
                it.seasonNumber > 0 &&
                it.episodeNumber > 0 &&
                (it.seasonNumber to it.episodeNumber) !in watchedKeys &&
                availableSeasons.any { season ->
                    season.seasonNumber == it.seasonNumber &&
                        it.episodeNumber <= season.episodeCount
                }
        }
        ?.let { progress ->
            return EpisodePlaybackTarget(
                seasonNumber = progress.seasonNumber,
                episodeNumber = progress.episodeNumber,
                isContinue = true,
                episodeTitle = episodeTitleFromProgress(progress),
            )
        }

    val latestWatched = watchedEpisodes
        .filter { it.seasonNumber > 0 && it.episodeNumber > 0 }
        .maxByOrNull { it.watchedAt }
    val preferredSeasonNumber = when {
        seasonSelectedByUser && selectedSeason != null -> selectedSeason
        latestWatched != null -> latestWatched.seasonNumber
        else -> selectedSeason
    }
    val preferredSeason = availableSeasons.firstOrNull {
        it.seasonNumber == preferredSeasonNumber
    } ?: availableSeasons.first()

    val lastWatchedInPreferredSeason = watchedEpisodes
        .filter {
            it.seasonNumber == preferredSeason.seasonNumber &&
                it.episodeNumber > 0
        }
        .maxByOrNull { it.watchedAt }

    val nextInPreferredSeason = lastWatchedInPreferredSeason?.let { lastWatched ->
        firstUnwatchedEpisode(
            season = preferredSeason,
            watchedKeys = watchedKeys,
            startEpisode = lastWatched.episodeNumber + 1,
        )
    } ?: firstUnwatchedEpisode(preferredSeason, watchedKeys)

    if (nextInPreferredSeason != null) {
        return EpisodePlaybackTarget(preferredSeason.seasonNumber, nextInPreferredSeason, false)
    }

    val nextSeasonTarget = availableSeasons
        .asSequence()
        .filter { it.seasonNumber > preferredSeason.seasonNumber }
        .mapNotNull { season ->
            firstUnwatchedEpisode(season, watchedKeys)?.let { season to it }
        }
        .firstOrNull()
    if (nextSeasonTarget != null) {
        return EpisodePlaybackTarget(
            seasonNumber = nextSeasonTarget.first.seasonNumber,
            episodeNumber = nextSeasonTarget.second,
            isContinue = false,
        )
    }

    val anyUnwatched = availableSeasons.firstNotNullOfOrNull { season ->
        firstUnwatchedEpisode(season, watchedKeys)?.let { season to it }
    }
    if (anyUnwatched != null) {
        return EpisodePlaybackTarget(
            seasonNumber = anyUnwatched.first.seasonNumber,
            episodeNumber = anyUnwatched.second,
            isContinue = false,
        )
    }

    // Everything is marked watched. Keep the main action useful for rewatches.
    return EpisodePlaybackTarget(preferredSeason.seasonNumber, 1, isContinue = false)
}

private fun firstUnwatchedEpisode(
    season: TmdbTvSeasonSummary,
    watchedKeys: Set<Pair<Int, Int>>,
    startEpisode: Int = 1,
): Int? {
    if (startEpisode > season.episodeCount) return null
    return (startEpisode..season.episodeCount)
        .firstOrNull { (season.seasonNumber to it) !in watchedKeys }
}

private fun episodeTitleFromProgress(progress: WatchProgressEntity): String? {
    val marker = " S${progress.seasonNumber}E${progress.episodeNumber}"
    val markerIndex = progress.title.lastIndexOf(marker)
    if (markerIndex < 0) return null
    return progress.title
        .substring(markerIndex + marker.length)
        .takeIf { it.startsWith(" · ") }
        ?.removePrefix(" · ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}