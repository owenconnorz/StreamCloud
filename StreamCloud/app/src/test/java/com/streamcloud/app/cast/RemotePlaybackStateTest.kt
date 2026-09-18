package com.streamcloud.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackStateTest {
    private val playing = RemotePlaybackState(
        destination = MusicRemoteCast.DestinationType.GoogleCast,
        deviceName = "Living Room TV",
        title = "In the Air Tonight",
        positionMs = 42_000L,
        durationMs = 320_000L,
        isPlaying = true,
    )

    @Test
    fun unknownStatusRetainsLastValidProgressAndPlayState() {
        val result = playing.withReceiverSample(
            positionMs = 0L,
            durationMs = 0L,
            phase = RemotePlaybackPhase.Unknown,
        )

        assertEquals(42_000L, result.positionMs)
        assertEquals(320_000L, result.durationMs)
        assertTrue(result.isPlaying)
        assertFalse(result.isBuffering)
    }

    @Test
    fun bufferingZeroPositionDoesNotJumpBackToStart() {
        val result = playing.withReceiverSample(
            positionMs = 0L,
            durationMs = null,
            phase = RemotePlaybackPhase.Buffering,
        )

        assertEquals(42_000L, result.positionMs)
        assertEquals(320_000L, result.durationMs)
        assertTrue(result.isPlaying)
        assertTrue(result.isBuffering)
    }

    @Test
    fun validPausedSampleUpdatesPositionAndPreservesDuration() {
        val result = playing.withReceiverSample(
            positionMs = 57_000L,
            durationMs = 0L,
            phase = RemotePlaybackPhase.Paused,
        )

        assertEquals(57_000L, result.positionMs)
        assertEquals(320_000L, result.durationMs)
        assertFalse(result.isPlaying)
        assertFalse(result.isBuffering)
    }
}