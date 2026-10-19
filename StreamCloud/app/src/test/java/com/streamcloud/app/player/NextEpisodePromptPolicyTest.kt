package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePromptPolicyTest {
    @Test
    fun countdownTracksTheFinalTwentySeconds() {
        assertEquals(20, nextEpisodeCountdownSeconds(20_000L))
        assertEquals(2, nextEpisodeCountdownSeconds(1_001L))
        assertEquals(1, nextEpisodeCountdownSeconds(1L))
        assertNull(nextEpisodeCountdownSeconds(20_001L))
        assertNull(nextEpisodeCountdownSeconds(0L))
    }

    @Test
    fun promptAppearsDuringPlaybackOnlyInTheFinalTwentySeconds() {
        assertTrue(
            shouldShowNextEpisodePrompt(
                hasNextEpisode = true,
                canPlayNext = true,
                dismissed = false,
                playbackEnded = false,
                isPlaying = true,
                durationMs = 1_000_000L,
                positionMs = 980_000L,
            ),
        )
        assertFalse(
            shouldShowNextEpisodePrompt(
                hasNextEpisode = true,
                canPlayNext = true,
                dismissed = false,
                playbackEnded = false,
                isPlaying = true,
                durationMs = 1_000_000L,
                positionMs = 979_999L,
            ),
        )
    }

    @Test
    fun endedEpisodesStillShowThePromptAndDismissedPromptsStayHidden() {
        val ended = shouldShowNextEpisodePrompt(
            hasNextEpisode = true,
            canPlayNext = true,
            dismissed = false,
            playbackEnded = true,
            isPlaying = false,
            durationMs = 0L,
            positionMs = 0L,
        )
        val dismissed = shouldShowNextEpisodePrompt(
            hasNextEpisode = true,
            canPlayNext = true,
            dismissed = true,
            playbackEnded = false,
            isPlaying = true,
            durationMs = 100_000L,
            positionMs = 90_000L,
        )

        assertTrue(ended)
        assertFalse(dismissed)
    }
}
