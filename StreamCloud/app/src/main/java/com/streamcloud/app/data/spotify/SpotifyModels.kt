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
    val playCount: Long = 0L,
)

data class SpotifyArtistOverview(
    val artist: SpotifyArtist,
    val headerImageUrl: String? = null,
    val biography: String = "",
    val verified: Boolean = false,
    val monthlyListeners: Long = 0L,
    val worldRank: Int? = null,
    val aboutImageUrl: String? = null,
    val topTracks: List<SpotifyTrack> = emptyList(),
    val popularReleases: List<SpotifyAlbum> = emptyList(),
    val featuringPlaylists: List<SpotifyPlaylist> = emptyList(),
    val artistPlaylists: List<SpotifyPlaylist> = emptyList(),
    val relatedArtists: List<SpotifyArtist> = emptyList(),
    val clips: List<SpotifyMusicVideo> = emptyList(),
    val saved: Boolean = false,
)

data class SpotifyAlbumDetails(
    val album: SpotifyAlbum,
    val albumType: String = "ALBUM",
    val label: String = "",
    val artists: List<SpotifyArtist> = emptyList(),
    val tracks: List<SpotifyTrack> = emptyList(),
    val moreByArtist: List<SpotifyAlbum> = emptyList(),
)

data class SpotifyMusicVideo(
    val id: String,
    val title: String,
    val imageUrl: String? = null,
    val durationMs: Long = 0L,
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

data class SpotifySearchResults(
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val podcasts: List<SpotifyPodcast> = emptyList(),
    val episodes: List<SpotifyEpisode> = emptyList(),
)

data class SpotifyPodcast(
    val id: String,
    val name: String,
    val publisher: String = "",
    val imageUrl: String? = null,
)

data class SpotifyEpisode(
    val id: String,
    val name: String,
    val podcast: String = "",
    val imageUrl: String? = null,
    val durationMs: Long = 0L,
)

data class SpotifyLibraryPage<T>(
    val items: List<T>,
    val totalCount: Int,
    val nextOffset: Int?,
)
