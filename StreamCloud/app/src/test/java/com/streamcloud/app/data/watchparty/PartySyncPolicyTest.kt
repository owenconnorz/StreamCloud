package com.streamcloud.app.data.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class PartySyncPolicyTest {
    @Test fun pausedPositionDoesNotAdvance() {
        assertEquals(9000L, projectedPartyPosition(9000, true, 1f, 1000, 10000))
    }
    @Test fun playingPositionIncludesTransitTime() {
        assertEquals(9500L, projectedPartyPosition(9000, false, 1f, 1000, 1500))
    }
    @Test fun speedIsIncludedInProjection() {
        assertEquals(10000L, projectedPartyPosition(9000, false, 2f, 1000, 1500))
    }
    @Test fun futureTimestampDoesNotSeekBackwards() {
        assertEquals(9000L, projectedPartyPosition(9000, false, 1f, 1500, 1000))
    }
    @Test fun disconnectedHostCannotExtrapolateForever() {
        assertEquals(39000L, projectedPartyPosition(9000, false, 1f, 1000, 900000))
    }
    @Test fun negativePositionCannotEscape() {
        assertEquals(0L, projectedPartyPosition(-1000, true, 1f, 1000, 1000))
    }
}
