package com.streamcloud.app.data.music

/** Stable IDs for built-in providers. External extensions are a separate, future contract. */
enum class MusicProvider(val id: String, val title: String, val description: String) {
    YouTubeMusic("youtube_music", "YouTube Music", "Recommendations, playlists and music discovery"),
    Offline("offline", "Offline downloads", "Music downloaded to this device"),
    Spotify("spotify", "Spotify", "Your playlists, albums, saved songs and followed artists");

    companion object {
        fun fromId(id: String?): MusicProvider =
            entries.firstOrNull { it.id == id } ?: YouTubeMusic
    }
}
