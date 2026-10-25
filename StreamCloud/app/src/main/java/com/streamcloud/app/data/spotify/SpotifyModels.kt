package com.streamcloud.app.data.spotify

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val imageUrl: String? = null,
    val trackCount: Int? = null,
    val snapshotId: String = "",
    val ownerName: String = "",
)

data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: String,
    val imageUrl: String? = null,
    val trackCount: Int? = null,
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

data class SpotifyHomeCard(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String? = null,
    val track: SpotifyTrack? = null,
    val collectionId: String? = null,
)

data class SpotifyHomeSection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val items: List<SpotifyHomeCard> = emptyList(),
)

data class SpotifyHomeFeed(
    val sections: List<SpotifyHomeSection> = emptyList(),
    val topArtists: List<SpotifyArtist> = emptyList(),
    val topTracks: List<SpotifyTrack> = emptyList(),
    val recentlyPlayed: List<SpotifyTrack> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class SpotifyLibraryPage<T>(
    val items: List<T>,
    val totalCount: Int,
    val nextOffset: Int?,
)
