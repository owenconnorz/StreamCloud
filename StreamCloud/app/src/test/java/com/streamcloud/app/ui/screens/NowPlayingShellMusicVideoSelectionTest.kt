package com.streamcloud.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingShellMusicVideoSelectionTest {

    @Test
    fun audioSelectionsDoNotDeriveInlineVideoState() {
        assertEquals(
            "",
            selectedMusicVideoId(
                isMusicVideo = false,
                explicitVideoId = "abc123def45",
                mediaId = "https://music.youtube.com/watch?v=abc123def45",
            ),
        )
    }

    @Test
    fun trackIdsRemainAvailableWithoutEnablingTheMusicVideoPlayer() {
        assertEquals(
            "abc123def45",
            mediaVideoId(
                explicitVideoId = "",
                mediaId = "https://music.youtube.com/watch?v=abc123def45",
            ),
        )
        assertEquals(
            "",
            selectedMusicVideoId(
                isMusicVideo = false,
                explicitVideoId = "",
                mediaId = "https://music.youtube.com/watch?v=abc123def45",
            ),
        )
    }

    @Test
    fun musicVideoSelectionsPreferExplicitMetadata() {
        assertEquals(
            "abc123def45",
            selectedMusicVideoId(
                isMusicVideo = true,
                explicitVideoId = "abc123def45",
                mediaId = null,
            ),
        )
        assertEquals(
            "https://cdn.example/video.mp4",
            selectedMusicVideoWatchUrl(
                explicitWatchUrl = "https://cdn.example/video.mp4",
                mediaId = "https://music.youtube.com/watch?v=ignored",
                videoId = "abc123def45",
            ),
        )
    }

    @Test
    fun musicVideoSelectionsFallBackToMediaIdParsing() {
        val mediaId = "https://music.youtube.com/watch?v=abc123def45&list=RDabc123def45"
        val videoId = selectedMusicVideoId(
            isMusicVideo = true,
            explicitVideoId = "",
            mediaId = mediaId,
        )

        assertEquals("abc123def45", videoId)
        assertEquals(mediaId, selectedMusicVideoWatchUrl("", mediaId, videoId))
    }
}
