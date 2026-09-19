package com.streamcloud.app.ui.screens

import com.streamcloud.app.data.ytmusic.MoodChip
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.YtmPlaylist
import com.streamcloud.app.data.ytmusic.YtmSong
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicScreenTest {

    @Test
    fun quickChipsKeepRemoteLabelsAndFillMissingCategories() {
        val chips = buildMusicQuickChips(
            listOf(MoodChip("Relax", null), MoodChip("Indie", null)),
        )

        assertEquals(listOf("Relax", "Indie", "Podcast", "Workout", "Focus", "Sleep", "Party", "Chill"), chips.map { it.label })
    }

    @Test
    fun combinedSuggestionsUseOneDeduplicatedBar() {
        val labels = buildCombinedMusicSuggestions(
            listOf(MoodChip("Chill", null), MoodChip("Podcast", null)),
        )

        assertEquals(
            listOf("Chill", "Podcast", "Top hits 2026", "Lo-fi beats", "Workout", "Throwback", "K-pop", "Hip hop", "Jazz", "EDM", "Acoustic"),
            labels,
        )
    }

    @Test
    fun homeFailureSummaryNamesEachUnavailableSource() {
        assertEquals(
            "YouTube Music: Sign in required\nYouTube fallback: Network unavailable",
            buildMusicHomeFailureSummary(
                ytMusicFailure = "Sign in required",
                fallbackFailure = "Network unavailable",
            ),
        )
    }

    @Test
    fun homeFailureSummaryIsAbsentWhenBothSourcesLoaded() {
        assertEquals(
            null,
            buildMusicHomeFailureSummary(
                ytMusicFailure = null,
                fallbackFailure = null,
            ),
        )
    }

    @Test
    fun speedDialUsesPinnedSongsBeforeTheHomeFeedFallback() {
        val pinned = ytmSong("pinned")
        val fallback = ytmSong("fallback")

        val entries = buildMusicSpeedDial(
            pinnedSongs = listOf(pinned),
            sections = listOf(HomeSection.SongRail("Listen again", listOf(fallback))),
        )

        assertEquals(listOf("pinned"), entries.map { it.key.removePrefix("song:") })
    }

    @Test
    fun speedDialFallsBackToHomePlaylistsAndSongsWhenNothingIsPinned() {
        val entries = buildMusicSpeedDial(
            pinnedSongs = emptyList(),
            sections = listOf(
                HomeSection.PlaylistRail(
                    "Listen again",
                    listOf(
                        YtmPlaylist("playlist-1", "Ours", null, null),
                        YtmPlaylist("playlist-2", "music", null, null),
                    ),
                ),
                HomeSection.SongRail(
                    "From the community",
                    listOf(ytmSong("song-1"), ytmSong("song-2")),
                ),
            ),
        )

        assertEquals(
            listOf(
                "playlist:playlist-1",
                "playlist:playlist-2",
                "song:song-1",
                "song:song-2",
            ),
            entries.map { it.key },
        )
    }

    private fun ytmSong(videoId: String) = YtmSong(
        videoId = videoId,
        title = videoId,
        artist = "Artist",
        album = null,
        thumbnail = null,
        durationSeconds = 180,
    )
}