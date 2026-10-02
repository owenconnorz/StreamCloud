package com.streamcloud.app.player

import com.streamcloud.app.data.api.TmdbEpisode
import com.streamcloud.app.data.api.TmdbTvSeasonSummary

internal data class BingeEpisodeQueueSelection(
    val episodes: List<BingeEpisode> = emptyList(),
    val currentIndex: Int = -1,
)

internal fun buildBingeEpisodeQueue(
    tmdbId: Long,
    showTitle: String,
    fallbackPosterUrl: String?,
    currentSeason: Int?,
    currentEpisode: Int?,
    seasonSummaries: List<TmdbTvSeasonSummary>,
    episodeDetails: List<TmdbEpisode>,
): BingeEpisodeQueueSelection {
    val seasonNumber = currentSeason ?: return BingeEpisodeQueueSelection()
    val episodeNumber = currentEpisode ?: return BingeEpisodeQueueSelection()
    if (tmdbId <= 0L || seasonNumber <= 0 || episodeNumber <= 0 || showTitle.isBlank()) {
        return BingeEpisodeQueueSelection()
    }

    val detailsByEpisode = episodeDetails.associateBy { it.seasonNumber to it.episodeNumber }
    val queueByEpisode = linkedMapOf<Pair<Int, Int>, BingeEpisode>()

    fun addEpisode(season: Int, episode: Int) {
        if (season <= 0 || episode <= 0) return
        val key = season to episode
        if (key in queueByEpisode) return

        val details = detailsByEpisode[key]
        val episodeTitle = details?.name?.takeIf { it.isNotBlank() }
        val posterUrl = details?.stillUrl
            ?: seasonSummaries.firstOrNull { it.seasonNumber == season }?.posterUrl
            ?: fallbackPosterUrl
        val progressTitle = buildString {
            append(showTitle)
            append(" S${season}E${episode}")
            episodeTitle?.let { append(" · $it") }
        }
        val progressKey = WatchProgressKey(
            tmdbId = tmdbId,
            title = progressTitle,
            posterUrl = posterUrl,
            mediaType = "tv",
            sourceRoute = sourceSelectionRoute("tv", season, episode),
            seasonNumber = season,
            episodeNumber = episode,
            showTitle = showTitle,
            episodeTitle = episodeTitle,
        )
        queueByEpisode[key] = BingeEpisode(
            tmdbId = tmdbId,
            title = showTitle,
            seasonNumber = season,
            episodeNumber = episode,
            posterUrl = posterUrl,
            episodeTitle = episodeTitle,
            progressKey = progressKey,
        )
    }

    episodeDetails
        .asSequence()
        .filter { it.seasonNumber == seasonNumber && it.episodeNumber > 0 }
        .sortedBy { it.episodeNumber }
        .forEach { addEpisode(it.seasonNumber, it.episodeNumber) }
    addEpisode(seasonNumber, episodeNumber)

    val nextLoadedEpisode = episodeDetails
        .asSequence()
        .filter { it.seasonNumber == seasonNumber && it.episodeNumber > episodeNumber }
        .minByOrNull { it.episodeNumber }
    if (nextLoadedEpisode != null) {
        addEpisode(nextLoadedEpisode.seasonNumber, nextLoadedEpisode.episodeNumber)
    } else {
        val seasonEpisodeCount = seasonSummaries
            .firstOrNull { it.seasonNumber == seasonNumber }
            ?.episodeCount
            ?: 0
        if (episodeNumber < seasonEpisodeCount) {
            addEpisode(seasonNumber, episodeNumber + 1)
        } else {
            seasonSummaries
                .asSequence()
                .filter { it.seasonNumber > seasonNumber && it.episodeCount > 0 }
                .minByOrNull { it.seasonNumber }
                ?.let { addEpisode(it.seasonNumber, 1) }
        }
    }

    val episodes = queueByEpisode.values.sortedWith(
        compareBy<BingeEpisode> { it.seasonNumber }.thenBy { it.episodeNumber },
    )
    val currentIndex = episodes.indexOfFirst {
        it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
    }
    return BingeEpisodeQueueSelection(episodes, currentIndex)
}