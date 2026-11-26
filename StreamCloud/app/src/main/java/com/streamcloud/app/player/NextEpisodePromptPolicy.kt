package com.streamcloud.app.player

internal const val NEXT_EPISODE_AUTOPLAY_COUNTDOWN_SECONDS = 10

internal fun shouldShowNextEpisodePrompt(
    hasNextEpisode: Boolean,
    canPlayNext: Boolean,
    dismissed: Boolean,
    playbackEnded: Boolean,
): Boolean {
    if (!hasNextEpisode || !canPlayNext || dismissed) return false
    return playbackEnded
}
