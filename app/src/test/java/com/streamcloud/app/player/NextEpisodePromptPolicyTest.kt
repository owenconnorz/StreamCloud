package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePromptPolicyTest {
    @Test
    fun prefersPlayerDurationAndFallsBackToTmdbRuntime() {
        assertEquals(1_800_000L, episodePromptDurationMs(1_800_000L, 2_400_000L))
        assertEquals(2_400_000L, episodePromptDurationMs(0L, 2_400_000L))
        assertEquals(0L, episodePromptDurationMs(0L, 0L))
    }

    @Test
    fun promptWindowAdaptsToEpisodeRuntimeWithinSafeBounds() {
        assertEquals(20_000L, nextEpisodePromptLeadMs(5 * 60_000L))
        assertEquals(24_000L, nextEpisodePromptLeadMs(20 * 60_000L))
        assertEquals(60_000L, nextEpisodePromptLeadMs(60 * 60_000L))
    }

    @Test
    fun countdownTracksTheAdaptiveEndWindowIncludingEstimatedEnd() {
        val durationMs = 20 * 60_000L
        assertEquals(24, nextEpisodeCountdownSeconds(24_000L, durationMs))
        assertEquals(2, nextEpisodeCountdownSeconds(1_001L, durationMs))
        assertEquals(1, nextEpisodeCountdownSeconds(1L, durationMs))
        assertEquals(0, nextEpisodeCountdownSeconds(0L, durationMs))
        assertNull(nextEpisodeCountdownSeconds(24_001L, durationMs))
    }

    @Test
    fun promptAppearsInAdaptiveWindowEvenWhenPlaybackIsPaused() {
        assertTrue(
            shouldShowNextEpisodePrompt(
                hasNextEpisode = true,
                canPlayNext = true,
                dismissed = false,
                playbackEnded = false,
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
                durationMs = 1_000_000L,
                positionMs = 979_999L,
            ),
        )
    }

    @Test
    fun promptAppearsAtEstimatedEndAndWaitsForActualPlaybackEndForAutoplay() {
        assertTrue(
            shouldShowNextEpisodePrompt(
                hasNextEpisode = true,
                canPlayNext = true,
                dismissed = false,
                playbackEnded = false,
                durationMs = 1_200_000L,
                positionMs = 1_176_000L,
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
            durationMs = 0L,
            positionMs = 0L,
        )
        val dismissed = shouldShowNextEpisodePrompt(
            hasNextEpisode = true,
            canPlayNext = true,
            dismissed = true,
            playbackEnded = false,
            durationMs = 100_000L,
            positionMs = 90_000L,
        )

        assertTrue(ended)
        assertFalse(dismissed)
    }
}
