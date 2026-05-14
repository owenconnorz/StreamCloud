package com.aioweb.app.player

/**
 * Hand-off between MovieDetailScreen and the player route.
 *
 * Stremio resolves a list of streams. We pick the best as the initial
 * playback URL, but the player needs the *full* sorted list for the
 * "Sources" picker. Encoding a 30+ item list through nav-route arguments
 * is ugly, so we just stash it in a singleton until the player consumes it.
 *
 * Also stashes the TMDB metadata (id, poster, media type) so the player
 * can save resume-position to the "Continue Watching" row without bloating
 * the navigation deeplink.
 *
 * Lives only for the lifetime of the process — fine, because the user
 * always re-enters the player by tapping "Play Movie" again on detail.
 */
object MoviePlayerSession {
    var sources: List<PlayerSource> = emptyList()
        private set

    /** Watch-progress descriptor for the currently-launched movie. */
    var progressKey: WatchProgressKey? = null
        private set

    fun set(newSources: List<PlayerSource>, progressKey: WatchProgressKey? = null) {
        sources = newSources
        this.progressKey = progressKey
    }

    fun clear() {
        sources = emptyList()
        progressKey = null
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
