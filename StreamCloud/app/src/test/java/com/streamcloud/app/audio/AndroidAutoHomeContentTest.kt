package com.streamcloud.app.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    @Test
        fun flattenSections_putsItemsInOneListAndPreservesPlaybackMetadata() {
            val song = MediaItem.Builder()
                .setMediaId("song-1")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Song")
                        .setArtist("Artist")
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
            val playlist = MediaItem.Builder()
                .setMediaId("playlist-1")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Mix")
                        .setSubtitle("Curated")
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build(),
                )
                .build()

            val flattened = AndroidAutoHomeContent.flattenSections(
                listOf(
                    "Speed dial" to listOf(song),
                    "Listen again" to listOf(song, playlist),
                ),
            )

            assertEquals(listOf("song-1", "playlist-1"), flattened.map(MediaItem::mediaId))
            assertEquals("Song", flattened[0].mediaMetadata.title.toString())
            assertEquals("Artist", flattened[0].mediaMetadata.artist.toString())
            assertEquals("Speed dial · Artist", flattened[0].mediaMetadata.subtitle.toString())
            assertTrue(flattened[0].mediaMetadata.isPlayable)
            assertFalse(flattened[0].mediaMetadata.isBrowsable)
            assertEquals("Listen again · Curated", flattened[1].mediaMetadata.subtitle.toString())
            assertTrue(flattened[1].mediaMetadata.isBrowsable)
            assertFalse(flattened[1].mediaMetadata.isPlayable)
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