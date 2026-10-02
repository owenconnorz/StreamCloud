package com.streamcloud.app.player

internal fun movieSeekTargetPosition(
    currentPositionMs: Long,
    durationMs: Long,
    seekIncrementSeconds: Int,
    direction: Int,
): Long {
    val upperBound = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    val current = currentPositionMs.coerceAtLeast(0L).coerceAtMost(upperBound)
    val stepMs = seekIncrementSeconds.coerceAtLeast(1).toLong() * 1_000L
    return if (direction < 0) {
        (current - stepMs).coerceAtLeast(0L)
    } else {
        val target = if (current > Long.MAX_VALUE - stepMs) Long.MAX_VALUE else current + stepMs
        target.coerceAtMost(upperBound)
    }
}