package com.streamcloud.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDisplayModesTest {
    @Test
    fun selects60HzAtCurrentResolutionInsteadOf120HzOrUpscaling() {
        val current = TvDisplayModeCandidate(
            modeId = 4,
            physicalWidth = 3840,
            physicalHeight = 2160,
            refreshRate = 120f,
        )
        val modes = listOf(
            TvDisplayModeCandidate(2, 3840, 2160, 60f),
            TvDisplayModeCandidate(3, 3840, 2160, 119.88f),
            current,
            TvDisplayModeCandidate(5, 3840, 2160, 144f),
            TvDisplayModeCandidate(6, 1920, 1080, 60f),
        )

        assertEquals(2, selectAutomaticTvDisplayModeId(current, modes))
    }

    @Test
    fun selects60HzWhen120HzIsUnavailable() {
        val current = TvDisplayModeCandidate(1, 3840, 2160, 24f)
        val modes = listOf(
            current,
            TvDisplayModeCandidate(2, 3840, 2160, 60f),
        )

        assertEquals(2, selectAutomaticTvDisplayModeId(current, modes))
    }

    @Test
    fun keepsAlreadySafeRefreshRateWithoutSwitchingModeIds() {
        val current = TvDisplayModeCandidate(1, 1920, 1080, 60f)
        val duplicateMode = TvDisplayModeCandidate(2, 1920, 1080, 60f)

        assertNull(selectAutomaticTvDisplayModeId(current, listOf(current, duplicateMode)))
    }

    @Test
    fun skipsAutomaticModeChangesOnLowRamDevices() {
        val current = TvDisplayModeCandidate(1, 1920, 1080, 120f)
        val safeMode = TvDisplayModeCandidate(2, 1920, 1080, 60f)

        assertNull(
            selectAutomaticTvDisplayModeId(
                current = current,
                supportedModes = listOf(current, safeMode),
                isLowRamDevice = true,
            ),
        )
    }

    @Test
    fun doesNotChangeResolutionWhenHdModesAreAdvertised() {
        val current = TvDisplayModeCandidate(1, 1280, 720, 120f)
        val modes = listOf(
            current,
            TvDisplayModeCandidate(2, 1920, 1080, 60f),
            TvDisplayModeCandidate(3, 3840, 2160, 60f),
        )

        assertNull(selectAutomaticTvDisplayModeId(current, modes))
    }

    @Test
    fun leavesDisplayUnchangedWhenNoCompatibleRefreshModeIsAdvertised() {
        val current = TvDisplayModeCandidate(1, 1280, 720, 120f)

        assertNull(selectAutomaticTvDisplayModeId(current, listOf(current)))
    }
}
