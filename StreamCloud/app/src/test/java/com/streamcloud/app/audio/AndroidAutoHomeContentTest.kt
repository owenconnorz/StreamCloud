package com.streamcloud.app.audio

import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.MoodChip
import com.streamcloud.app.data.ytmusic.YtMusicHomeFeed
import com.streamcloud.app.data.ytmusic.YtmPlaylist
import com.streamcloud.app.data.ytmusic.YtmSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoHomeContentTest {

    @Test
    fun speedDialAndQuickPicks_prioritizeTheirSourcesAndRemoveDuplicates() {
        val recent = listOf(track("recent-1"), track("shared"))
        val liked = listOf(track("liked-1"), track("shared"))
        val mostPlayed = listOf(track("played-1", playCount = 3), track("shared", playCount = 2))

        assertEquals(
            listOf("recent-1", "shared", "liked-1", "played-1"),
            AndroidAutoHomeContent.speedDialTracks(recent, liked, mostPlayed).map { it.url },
        )
        assertEquals(
            listOf("played-1", "shared", "recent-1", "liked-1"),
            AndroidAutoHomeContent.quickPickTracks(mostPlayed, recent, liked).map { it.url },
        )
    }

    @Test
    fun feedShelves_keepArtworkAndSkipEmptyOrNonPlayableSections() {
        val feed = YtMusicHomeFeed(
            sections = listOf(
                HomeSection.MoodChips("Moods", listOf(MoodChip("Chill", null))),
                HomeSection.SongRail(
                    "Quick picks",
                    listOf(song("one", "Song", "Artist", "https://img/song.jpg")),
                ),
                HomeSection.PlaylistRail(
                    "Empty rail",
                    emptyList(),
                ),
                HomeSection.PlaylistRail(
                    "Mixes for you",
                    listOf(YtmPlaylist("mix", "Mix", "https://img/mix.jpg", null)),
                ),
            ),
        )

        val shelves = AndroidAutoHomeContent.feedShelves(feed)

        assertEquals(
            listOf("yths_1", "yths_3"),
            shelves.map { it.mediaId },
        )
        assertEquals(
            listOf("Quick picks", "Mixes for you"),
            shelves.map { it.title },
        )
        assertEquals(
            listOf("https://img/song.jpg", "https://img/mix.jpg"),
            shelves.map { it.artworkUrl },
        )
        assertTrue(AndroidAutoHomeContent.hasQuickPicksShelf(shelves))
    }

    @Test
    fun quickPicksShelf_isAbsentWhenFeedUsesOtherRecommendations() {
        val shelves = AndroidAutoHomeContent.feedShelves(
            YtMusicHomeFeed(
                sections = listOf(
                    HomeSection.SongRail("Made for you", listOf(song("one", "Song", "Artist", null))),
                ),
            ),
        )

        assertFalse(AndroidAutoHomeContent.hasQuickPicksShelf(shelves))
    }

    private fun track(url: String, playCount: Int = 0) = TrackEntity(
        url = url,
        title = url,
        artist = "Artist",
        durationSec = 180L,
        thumbnail = null,
        playCount = playCount,
    )

    private fun song(id: String, title: String, artist: String, thumbnail: String?) = YtmSong(
        videoId = id,
        title = title,
        artist = artist,
        album = null,
        thumbnail = thumbnail,
        durationSeconds = null,
    )
}