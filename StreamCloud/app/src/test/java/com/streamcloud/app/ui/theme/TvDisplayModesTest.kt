package com.streamcloud.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDisplayModesTest {
    @Test
    fun selectsClosest120HzModeAtCurrentResolution() {
        val current = TvDisplayModeCandidate(
            modeId = 1,
            physicalWidth = 3840,
            physicalHeight = 2160,
            refreshRate = 60f,
        )
        val modes = listOf(
            TvDisplayModeCandidate(2, 1920, 1080, 120f),
            TvDisplayModeCandidate(3, 3840, 2160, 119.88f),
            TvDisplayModeCandidate(4, 3840, 2160, 120f),
            TvDisplayModeCandidate(5, 3840, 2160, 144f),
        )

        assertEquals(4, selectAutomaticHighRefreshModeId(current, modes))
    }

    @Test
    fun leavesDisplayUnchangedWhenNoMatching120HzModeExists() {
        val current = TvDisplayModeCandidate(1, 3840, 2160, 60f)
        val modes = listOf(
            TvDisplayModeCandidate(2, 1920, 1080, 120f),
            TvDisplayModeCandidate(3, 3840, 2160, 60f),
            TvDisplayModeCandidate(4, 3840, 2160, 144f),
        )

        assertNull(selectAutomaticHighRefreshModeId(current, modes))
    }

    @Test
    fun doesNotRequestAnAlreadyActive120HzMode() {
        val current = TvDisplayModeCandidate(2, 3840, 2160, 120f)
        val modes = listOf(current, TvDisplayModeCandidate(3, 3840, 2160, 60f))

        assertNull(selectAutomaticHighRefreshModeId(current, modes))
    }
}
