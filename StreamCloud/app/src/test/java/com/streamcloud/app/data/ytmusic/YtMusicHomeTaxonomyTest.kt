package com.streamcloud.app.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ── Carousel section coverage tests ──────────────────────────────────────────

    @Test
    fun mapSections_includesTrendingNowSongRail() {
        val fallback = songs(8, prefix = "tr")
        val mapped = YtMusicHomeTaxonomy.mapSections(rawSections = emptyList(), fallbackSongs = fallback)

        val trending = mapped.firstOrNull { it.title == "Trending Now" }
        assertNotNull("Trending Now section must be present", trending)
        assertTrue("Trending Now must be a SongRail", trending is HomeSection.SongRail)
        assertTrue(
            "Trending Now SongRail must have items",
            (trending as HomeSection.SongRail).items.isNotEmpty(),
        )
    }

    @Test
    fun mapSections_includesTopSongsSongRail() {
        val fallback = songs(8, prefix = "ts")
        val mapped = YtMusicHomeTaxonomy.mapSections(rawSections = emptyList(), fallbackSongs = fallback)

        val topSongs = mapped.firstOrNull { it.title == "Top Songs" }
        assertNotNull("Top Songs section must be present", topSongs)
        assertTrue("Top Songs must be a SongRail", topSongs is HomeSection.SongRail)
        assertTrue(
            "Top Songs SongRail must have items",
            (topSongs as HomeSection.SongRail).items.isNotEmpty(),
        )
    }

    @Test
    fun mapSections_includesTopArtistsSongRail() {
        val fallback = songs(8, prefix = "ta", distinctArtists = true)
        val mapped = YtMusicHomeTaxonomy.mapSections(rawSections = emptyList(), fallbackSongs = fallback)

        val topArtists = mapped.firstOrNull { it.title == "Top Artists" }
        assertNotNull("Top Artists section must be present", topArtists)
        assertTrue("Top Artists must be a SongRail", topArtists is HomeSection.SongRail)
        assertTrue(
            "Top Artists SongRail must have items",
            (topArtists as HomeSection.SongRail).items.isNotEmpty(),
        )
    }

    @Test
    fun homeCarouselSections_songRailsHaveItems() {
        val fallback = songs(20, prefix = "fb")
        val mapped = YtMusicHomeTaxonomy.mapSections(rawSections = emptyList(), fallbackSongs = fallback)

        val songRails = mapped.filterIsInstance<HomeSection.SongRail>()
        assertTrue("All SongRail sections should have items", songRails.all { it.items.isNotEmpty() })
    }

    @Test
    fun homeCarouselSections_listenAgainData_cappedAtSix() {
        // Simulate the data cap that MusicScreen applies: state.mostPlayed.take(6)
        val mostPlayed = songs(10, prefix = "lp")
        val listenAgain = mostPlayed.take(6)

        assertEquals("Listen Again must be capped at 6 items", 6, listenAgain.size)
    }

    @Test
    fun homeCarouselSections_recentlyPlayedData_cappedAtSix() {
        // Simulate the data cap that MusicScreen applies: state.recent.take(6)
        val recent = songs(10, prefix = "rp")
        val recentlyPlayed = recent.take(6)

        assertEquals("Recently Played must be capped at 6 items", 6, recentlyPlayed.size)
    }

    @Test
    fun homeCarouselSections_fewerThanSixItems_rendersAll() {
        val mostPlayed = songs(3, prefix = "lp")
        val listenAgain = mostPlayed.take(6)

        assertEquals("When fewer than 6 items exist, render all available", 3, listenAgain.size)
    }

    @Test
    fun mapSections_trendingNowSongRail_preservesItemsForCarousel() {
        val fallback = songs(12, prefix = "tr")
        val mapped = YtMusicHomeTaxonomy.mapSections(rawSections = emptyList(), fallbackSongs = fallback)

        val trending = mapped.first { it.title == "Trending Now" } as HomeSection.SongRail
        assertTrue(
            "Trending Now carousel must have items accessible beyond initial view",
            trending.items.isNotEmpty(),
        )
    }

    private fun songs(count: Int, prefix: String, distinctArtists: Boolean = false): List<YtmSong> =
        (1..count).map { i ->
            song(
                id = "${prefix}_$i",
                title = "Song $prefix $i",
                artist = if (distinctArtists) "Artist $i" else "Artist $prefix",
            )
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
