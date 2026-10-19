package com.streamcloud.app.player

internal const val NEXT_EPISODE_PROMPT_LEAD_MS = 20_000L

internal fun nextEpisodeCountdownSeconds(remainingMs: Long): Int? {
    if (remainingMs !in 1L..NEXT_EPISODE_PROMPT_LEAD_MS) return null
    return ((remainingMs + 999L) / 1_000L).toInt()
}

internal fun shouldShowNextEpisodePrompt(
    hasNextEpisode: Boolean,
    canPlayNext: Boolean,
    dismissed: Boolean,
    playbackEnded: Boolean,
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: Long,
): Boolean {
    if (!hasNextEpisode || !canPlayNext || dismissed) return false
    if (playbackEnded) return true
    if (!isPlaying || durationMs <= 0L) return false
    return nextEpisodeCountdownSeconds(durationMs - positionMs) != null
}
