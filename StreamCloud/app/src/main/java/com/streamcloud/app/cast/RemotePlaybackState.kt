package com.streamcloud.app.cast

/**
 * The receiver-owned playback state used by every casting surface.
 *
 * Position and duration are deliberately nullable: a receiver can be connected while it is
 * still loading its first status update. Consumers must retain/display the last known values
 * instead of treating that transient state as a reset to zero.
 */
data class RemotePlaybackState(
    val destination: MusicRemoteCast.DestinationType,
    val deviceName: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

internal enum class RemotePlaybackPhase {
    Playing,
    Paused,
    Buffering,
    Idle,
    Unknown,
}

/**
 * Merges a receiver sample without allowing an incomplete status response to erase useful state.
 */
internal fun RemotePlaybackState.withReceiverSample(
    positionMs: Long?,
    durationMs: Long?,
    phase: RemotePlaybackPhase,
): RemotePlaybackState {
    val safePosition = positionMs?.coerceAtLeast(0L)
    val mergedPosition = when {
        safePosition == null -> this.positionMs
        phase == RemotePlaybackPhase.Unknown -> this.positionMs
        phase == RemotePlaybackPhase.Buffering &&
            safePosition == 0L &&
            (this.positionMs ?: 0L) > 0L -> this.positionMs
        else -> safePosition
    }
    val mergedDuration = durationMs
        ?.takeIf { it > 0L }
        ?: this.durationMs
    val mergedPlaying = when (phase) {
        RemotePlaybackPhase.Playing -> true
        RemotePlaybackPhase.Paused,
        RemotePlaybackPhase.Idle -> false
        RemotePlaybackPhase.Buffering,
        RemotePlaybackPhase.Unknown -> isPlaying
    }
    return copy(
        positionMs = mergedPosition,
        durationMs = mergedDuration,
        isPlaying = mergedPlaying,
        isBuffering = phase == RemotePlaybackPhase.Buffering,
    )
}