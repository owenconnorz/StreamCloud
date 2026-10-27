package com.streamcloud.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDisplayModesTest {
    @Test
    fun selectsHighestRefreshUpTo120AtCurrentHdResolution() {
        val current = TvDisplayModeCandidate(
            modeId = 1,
            physicalWidth = 3840,
            physicalHeight = 2160,
            refreshRate = 60f,
        )
        val modes = listOf(
            TvDisplayModeCandidate(2, 3840, 2160, 60f),
            TvDisplayModeCandidate(3, 3840, 2160, 119.88f),
            TvDisplayModeCandidate(4, 3840, 2160, 120f),
            TvDisplayModeCandidate(5, 3840, 2160, 144f),
            TvDisplayModeCandidate(6, 1920, 1080, 120f),
        )

        assertEquals(4, selectAutomaticTvDisplayModeId(current, modes))
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
    fun upgradesBelowHdResolutionToHighestSupportedHdResolution() {
        val current = TvDisplayModeCandidate(1, 1280, 720, 60f)
        val modes = listOf(
            current,
            TvDisplayModeCandidate(2, 1920, 1080, 120f),
            TvDisplayModeCandidate(3, 3840, 2160, 60f),
        )

        assertEquals(3, selectAutomaticTvDisplayModeId(current, modes))
    }

    @Test
    fun leavesDisplayUnchangedWhenNoHdModeIsAdvertised() {
        val current = TvDisplayModeCandidate(1, 1280, 720, 60f)

        assertNull(selectAutomaticTvDisplayModeId(current, listOf(current)))
    }
}
