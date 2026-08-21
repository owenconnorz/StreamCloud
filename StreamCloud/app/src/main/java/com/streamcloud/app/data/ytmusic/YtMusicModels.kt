package com.streamcloud.app.data.ytmusic

import java.util.concurrent.ConcurrentHashMap

data class YtmPlaylist(

    val id: String,
    val title: String,
    val thumbnail: String?,

    val subtitle: String?,
    val isAlbum: Boolean = false,
    /** True when this item is a standalone music video (videoId only, no browse/playlist ID).
     *  Clicking it should play the video directly, not open a playlist page. */
    val isVideo: Boolean = false,

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
    val isVideo: Boolean = false,
    /**
     * Identifies this occurrence within an editable YouTube Music playlist.
     * A song can appear multiple times, so the video ID alone is not always sufficient to remove
     * the intended playlist entry.
     */
    val playlistSetVideoId: String? = null,
)

/**
 * Preserves a home-card cover while navigating to its playlist screen.
 *
 * A card thumbnail is a URL and can be altered by route encoding/decoding. Keeping this
 * process-local handoff keyed by the exact browse ID makes the detail screen independent of
 * navigation argument parsing. The route thumbnail remains as a process-death fallback.
 */
object YtPlaylistArtworkHandoff {
    private val artwork = ConcurrentHashMap<String, String>()

    fun remember(playlistId: String, thumbnail: String?) {
        thumbnail?.takeIf { it.isNotBlank() }?.let { artwork[playlistId] = it }
    }

    fun get(playlistId: String): String? = artwork[playlistId]
}

data class YtMusicLibrary(
    val likedSongs: List<YtmSong> = emptyList(),
    val playlists: List<YtmPlaylist> = emptyList(),
    val albums: List<YtmPlaylist> = emptyList(),
    val artists: List<YtmLibraryArtist> = emptyList(),
    val syncedAt: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
)
