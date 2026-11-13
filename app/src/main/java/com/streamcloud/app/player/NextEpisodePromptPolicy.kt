package com.streamcloud.app.player

internal const val NEXT_EPISODE_PROMPT_MIN_LEAD_MS = 20_000L
internal const val NEXT_EPISODE_PROMPT_MAX_LEAD_MS = 60_000L

internal fun episodePromptDurationMs(playerDurationMs: Long, tmdbRuntimeMs: Long): Long =
    playerDurationMs.takeIf { it > 0L }
        ?: tmdbRuntimeMs.takeIf { it > 0L }
        ?: 0L

internal fun nextEpisodePromptLeadMs(durationMs: Long): Long =
    (durationMs / 50L).coerceIn(
        NEXT_EPISODE_PROMPT_MIN_LEAD_MS,
        NEXT_EPISODE_PROMPT_MAX_LEAD_MS,
    )

internal fun nextEpisodeCountdownSeconds(remainingMs: Long, durationMs: Long): Int? {
    if (remainingMs !in 0L..nextEpisodePromptLeadMs(durationMs)) return null
    if (remainingMs == 0L) return 0
    return ((remainingMs + 999L) / 1_000L).toInt()
}

internal fun shouldShowNextEpisodePrompt(
    hasNextEpisode: Boolean,
    canPlayNext: Boolean,
    dismissed: Boolean,
    playbackEnded: Boolean,
    durationMs: Long,
    positionMs: Long,
): Boolean {
    if (!hasNextEpisode || !canPlayNext || dismissed) return false
    if (playbackEnded) return true
    if (durationMs <= 0L) return false
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    return remainingMs <= nextEpisodePromptLeadMs(durationMs)
}
