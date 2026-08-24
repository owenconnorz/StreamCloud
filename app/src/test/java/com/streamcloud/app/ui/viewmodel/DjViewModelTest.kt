package com.streamcloud.app.ui.viewmodel

import com.streamcloud.app.audio.DjVoicePreset
import com.streamcloud.app.data.newpipe.YtTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjViewModelTest {

    @Test
    fun distinctTracksRemovesSameVideoAcrossYouTubeUrlShapes() {
        val tracks = distinctDjTracks(
            listOf(
                track("https://www.youtube.com/watch?v=abc123def45"),
                track("https://music.youtube.com/watch?v=abc123def45"),
                track("https://www.youtube.com/watch?v=othervideo1"),
            ),
        )

        assertEquals(2, tracks.size)
    }

    @Test
    fun discoveryQueriesBlendLocalAndOnlineSourcesWithoutDuplicates() {
        val queries = buildDjDiscoveryQueries(
            seedTracks = listOf(track("https://music.youtube.com/watch?v=abc123def45")),
            searchHistory = listOf("Dream pop", "dream pop"),
            onlineQueries = listOf(
                "Artist popular songs",
                "Saved album similar music",
                "Home playlist music",
            ),
        )

        assertTrue(queries.any { it.equals("Dream pop", ignoreCase = true) })
        assertEquals(1, queries.count { it.equals("Dream pop", ignoreCase = true) })
        assertTrue(queries.contains("Artist popular songs"))
        assertTrue(queries.contains("Saved album similar music"))
        assertTrue(queries.contains("Home playlist music"))
    }

    @Test
    fun onlineSourcesKeepReservedSeedSlotsWhenHistoryIsLarge() {
        val seeds = buildDjSeedTracks(
            localTracks = (1..10).map { track("https://music.youtube.com/watch?v=local0000$it") },
            onlineLikedTracks = listOf(track("https://music.youtube.com/watch?v=liked000001")),
            onlineCollectionTracks = listOf(track("https://music.youtube.com/watch?v=playlist001")),
            onlineHomeTracks = listOf(track("https://music.youtube.com/watch?v=homefeed001")),
        )

        assertTrue(seeds.any { it.url.contains("liked000001") })
        assertTrue(seeds.any { it.url.contains("playlist001") })
        assertTrue(seeds.any { it.url.contains("homefeed001") })
    }

    @Test
    fun sultryVoiceStyleRemainsAnOriginalPreset() {
        assertEquals("Sultry host", DjVoicePreset.SultryHost.label)
    }

    private fun track(url: String) = YtTrack(
        title = "Example song",
        uploader = "Example artist",
        durationSec = 180,
        url = url,
        thumbnail = null,
    )
}