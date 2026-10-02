package com.streamcloud.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieSeekPolicyTest {
    @Test
    fun seeksByConfiguredIncrementInEitherDirection() {
        assertEquals(80_000L, movieSeekTargetPosition(90_000L, 300_000L, 10, -1))
        assertEquals(100_000L, movieSeekTargetPosition(90_000L, 300_000L, 10, 1))
    }

    @Test
    fun clampsSeekToTheStartAndEndOfTheMovie() {
        assertEquals(0L, movieSeekTargetPosition(5_000L, 60_000L, 10, -1))
        assertEquals(60_000L, movieSeekTargetPosition(55_000L, 60_000L, 10, 1))
    }
}