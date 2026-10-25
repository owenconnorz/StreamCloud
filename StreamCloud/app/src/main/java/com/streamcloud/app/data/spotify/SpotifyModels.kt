package com.streamcloud.app.data.spotify

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val imageUrl: String? = null,
    val trackCount: Int = 0,
    val snapshotId: String = "",
    val ownerName: String = "",
)

data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: String,
    val imageUrl: String? = null,
    val trackCount: Int = 0,
    val releaseDate: String = "",
)

data class SpotifyArtist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val followers: Long = 0,
)

data class SpotifyTrack(
    val id: String,
    val uri: String,          // spotify:track:xxxx
    val title: String,
    val artists: String,      // comma-joined artist names
    val album: String = "",
    val imageUrl: String? = null,
    val durationMs: Long = 0L,
    val artistIds: List<String> = emptyList(),
)

data class SpotifyHomeFeed(
    val topArtists: List<SpotifyArtist> = emptyList(),
    val topTracks: List<SpotifyTrack> = emptyList(),
    val recentlyPlayed: List<SpotifyTrack> = emptyList(),
    val warnings: List<String> = emptyList(),
)
