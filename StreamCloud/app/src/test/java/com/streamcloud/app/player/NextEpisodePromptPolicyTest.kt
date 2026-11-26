package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePromptPolicyTest {
    @Test
    fun autoplayCountdownIsTenSeconds() {
        assertEquals(10, NEXT_EPISODE_AUTOPLAY_COUNTDOWN_SECONDS)
    }

    @Test
    fun promptWaitsUntilPlaybackEnds() {
        assertFalse(
            shouldShowNextEpisodePrompt(
                hasNextEpisode = true,
                canPlayNext = true,
                dismissed = false,
                playbackEnded = false,
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
        )
        val dismissed = shouldShowNextEpisodePrompt(
            hasNextEpisode = true,
            canPlayNext = true,
            dismissed = true,
            playbackEnded = false,
        )

        assertTrue(ended)
        assertFalse(dismissed)
    }
}
