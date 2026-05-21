package com.streamcloud.app.data.ytmusic

/** Lightweight models for YouTube Music library content — parsed from InnerTube responses. */

data class YtmPlaylist(
    /** Playlist id as YT uses it in URLs (e.g. `PL...` or `VLPL...`). */
    val id: String,
    val title: String,
    val thumbnail: String?,
    /** Human-friendly subtitle — typically "Playlist · N songs" or "Album · YEAR". */
    val subtitle: String?,
    val isAlbum: Boolean = false,
    /**
     * Real track count from the last full pagination of this playlist.
     *
     * YouTube's library-browse API returns a stale/approximate count in the
     * subtitle text (e.g. "Playlist · 31 songs") that often disagrees with the
     * actual paginated track count (e.g. 380). This field is populated from the
     * real paginated result and persisted in [LibraryCache] so the card always
     * shows the correct number once the playlist has been opened at least once.
     *
     * Null until the playlist has been loaded for the first time in this install.
     */
    val cachedTrackCount: Int? = null,
)

data class YtmLibraryArtist(
    val channelId: String,
    val name: String,
    val thumbnail: String?,
    val subtitle: String?,
)

data class YtmSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val thumbnail: String?,
    val durationSeconds: Long?,
)

/** Top-level result of a library sync. Any failed subfield becomes an empty list. */
data class YtMusicLibrary(
    val likedSongs: List<YtmSong> = emptyList(),
    val playlists: List<YtmPlaylist> = emptyList(),
    val albums: List<YtmPlaylist> = emptyList(),
    val artists: List<YtmLibraryArtist> = emptyList(),
    val syncedAt: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
)
