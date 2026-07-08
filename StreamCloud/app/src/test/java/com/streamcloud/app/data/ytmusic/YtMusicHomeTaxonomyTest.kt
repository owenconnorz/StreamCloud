package com.streamcloud.app.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicHomeTaxonomyTest {

    @Test
    fun mapSections_returnsApprovedOrder() {
        val raw = listOf(
            HomeSection.SongRail("Quick picks from YouTube Music", listOf(song("a", "Song A", "Artist A"))),
            HomeSection.PlaylistRail("New releases", listOf(playlist("pl1", "Album 1"))),
            HomeSection.MoodChips("Moods", listOf(MoodChip("Chill", null))),
        )

        val mapped = YtMusicHomeTaxonomy.mapSections(raw, fallbackSongs = listOf(song("b", "Song B", "Artist B")))

        assertEquals(
            listOf(
                "Quick Picks",
                "Recommended for You",
                "New Releases",
                "Trending Now",
                "Top Songs",
                "Top Albums",
                "Top Artists",
                "Mood & Genres",
                "Featured Playlists",
                "Your Mixes",
                "Music Videos",
            ),
            mapped.map { it.title },
        )
    }

    @Test
    fun mapSections_usesFallbacks_whenBackendSectionsMissing() {
        val fallback = listOf(song("v1", "Fallback Song", "Fallback Artist", isVideo = true))

        val mapped = YtMusicHomeTaxonomy.mapSections(rawSections = emptyList(), fallbackSongs = fallback)

        assertEquals(11, mapped.size)
        assertTrue(mapped.filterIsInstance<HomeSection.SongRail>().any { it.items.isNotEmpty() })
        assertTrue(mapped.filterIsInstance<HomeSection.MoodChips>().single().chips.isNotEmpty())
    }

    private fun song(id: String, title: String, artist: String, isVideo: Boolean = false) = YtmSong(
        videoId = id,
        title = title,
        artist = artist,
        album = null,
        thumbnail = null,
        durationSeconds = null,
        isVideo = isVideo,
    )

    private fun playlist(id: String, title: String) = YtmPlaylist(
        id = id,
        title = title,
        thumbnail = null,
        subtitle = null,
    )
}
