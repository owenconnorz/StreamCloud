package com.streamcloud.app.data.spotify

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val imageUrl: String? = null,
    val trackCount: Int = 0,
    val snapshotId: String = "",
)

data class SpotifyTrack(
    val id: String,
    val uri: String,          // spotify:track:xxxx
    val title: String,
    val artists: String,      // comma-joined artist names
    val album: String = "",
    val imageUrl: String? = null,
    val durationMs: Long = 0L,
)
