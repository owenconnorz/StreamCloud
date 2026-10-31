package com.streamcloud.app.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDpadRepeatPolicyTest {
    @Test
    fun initialPressDoesNotUseRepeatThrottle() {
        assertFalse(
            shouldMoveFocusForDpadRepeat(
                repeatCount = 0,
                elapsedSinceLastRepeatMs = 1_000L,
                gateMs = 80L,
            ),
        )
    }

    @Test
    fun heldRepeatWaitsForGate() {
        assertFalse(
            shouldMoveFocusForDpadRepeat(
                repeatCount = 1,
                elapsedSinceLastRepeatMs = 79L,
                gateMs = 80L,
            ),
        )
    }

    @Test
    fun heldRepeatMovesAtGateBoundary() {
        assertTrue(
            shouldMoveFocusForDpadRepeat(
                repeatCount = 1,
                elapsedSinceLastRepeatMs = 80L,
                gateMs = 80L,
            ),
        )
    }
}
