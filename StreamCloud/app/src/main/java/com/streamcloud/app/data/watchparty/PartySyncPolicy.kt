package com.streamcloud.app.data.watchparty

/** Bound stale extrapolation; never turn an offline host into an unlimited seek. */
internal fun projectedPartyPosition(
    positionMs: Long,
    paused: Boolean,
    rate: Float,
    at: Long,
    serverNow: Long,
): Long {
    val elapsed = if (paused) 0L else (serverNow - at).coerceIn(0L, 30_000L)
    return (positionMs + (elapsed * rate).toLong()).coerceAtLeast(0L)
}
