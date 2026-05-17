package com.aioweb.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off between MovieDetailScreen and the player route.
 *
 * Sources are now a [StateFlow] so the player composable observes live updates.
 * When the user taps the refresh button, the player re-runs Nuvio resolution and
 * calls [mergeSources] — the sheet updates instantly without leaving the player.
 *
 * Also stores the TMDB metadata (id, poster, media type) so the player can
 * save resume-position to the "Continue Watching" row without bloating the
 * navigation deeplink, and so the refresh path knows which movie to query.
 */
object MoviePlayerSession {

    private val _sources = MutableStateFlow<List<PlayerSource>>(emptyList())
    val sourcesFlow: StateFlow<List<PlayerSource>> = _sources.asStateFlow()

    /** Non-reactive read for callers that just need the current snapshot. */
    val sources: List<PlayerSource> get() = _sources.value

    var progressKey: WatchProgressKey? = null
        private set

    /** TMDB numeric id — used by the refresh path to re-query Nuvio providers. */
    var tmdbId: Long = 0L
        private set

    /** Media type — "movie" or "tv". */
    var mediaType: String = "movie"
        private set

    fun set(
        newSources: List<PlayerSource>,
        progressKey: WatchProgressKey? = null,
        tmdbId: Long = 0L,
        mediaType: String = "movie",
    ) {
        _sources.value = newSources
        this.progressKey = progressKey
        this.tmdbId = tmdbId
        this.mediaType = mediaType
    }

    /**
     * Merges [additionalSources] into the current list, deduplicating by id.
     * Combined list is re-sorted so the best source stays at the top.
     */
    fun mergeSources(additionalSources: List<PlayerSource>) {
        if (additionalSources.isEmpty()) return
        val existing = _sources.value
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val genuinelyNew = additionalSources.filter { it.id !in existingIds }
        if (genuinelyNew.isEmpty()) return
        fun score(s: PlayerSource): Int {
            val q = when (s.qualityTag) {
                "4K" -> 5; "1440p" -> 4; "1080p" -> 3; "720p" -> 2; "480p" -> 1; else -> 0
            }
            return q * 10 + if (!s.isMagnet) 1 else 0
        }
        _sources.value = (existing + genuinelyNew).sortedByDescending { score(it) }
    }

    fun clear() {
        _sources.value = emptyList()
        progressKey = null
        tmdbId = 0L
        mediaType = "movie"
    }
}

/**
 * Identifies a movie/episode for the resume-playback row. Carried alongside
 * the [PlayerSource] list when launching the player.
 */
data class WatchProgressKey(
    val tmdbId: Long,
    val title: String,
    val posterUrl: String?,
    val mediaType: String, // "movie" or "tv"
)
