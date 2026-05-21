package com.streamcloud.app.data.ytmusic

data class YtmPlaylist(

    val id: String,
    val title: String,
    val thumbnail: String?,

    val subtitle: String?,
    val isAlbum: Boolean = false,

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

data class YtMusicLibrary(
    val likedSongs: List<YtmSong> = emptyList(),
    val playlists: List<YtmPlaylist> = emptyList(),
    val albums: List<YtmPlaylist> = emptyList(),
    val artists: List<YtmLibraryArtist> = emptyList(),
    val syncedAt: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
)
