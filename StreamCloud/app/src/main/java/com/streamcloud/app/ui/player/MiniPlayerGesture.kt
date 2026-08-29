package com.streamcloud.app.ui.player

import kotlin.math.abs
import kotlin.math.max

/**
 * Metrolist-style horizontal swipe thresholds. A quick swipe may commit after
 * the minimum distance; a slow drag must cross the larger automatic threshold.
 */
internal const val SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX = 50f
internal const val SWIPE_HORIZONTAL_AUTO_THRESHOLD_PX = 400f
internal const val SWIPE_HORIZONTAL_VELOCITY_THRESHOLD_PX_PER_MS = 2.5f
internal const val PLAYER_SURFACE_FLING_VELOCITY_PX_PER_MS = 1.2f

internal fun resolveMiniPlayerDragAxis(
    totalX: Float,
    totalY: Float,
    touchSlop: Float,
): MiniPlayerDragAxis {
    if (max(abs(totalX), abs(totalY)) < touchSlop) {
        return MiniPlayerDragAxis.Undecided
    }
    return if (abs(totalX) > abs(totalY)) {
        MiniPlayerDragAxis.Horizontal
    } else {
        MiniPlayerDragAxis.Vertical
    }
}

internal enum class MiniPlayerDragAxis {
    Undecided,
    Horizontal,
    Vertical,
}

internal fun shouldCommitMiniPlayerTrackSwipe(
    offsetX: Float,
    totalDistance: Float,
    durationMs: Long,
    canChangeSong: Boolean,
): Boolean {
    if (!canChangeSong) return false
    val velocity = if (durationMs > 0L) totalDistance / durationMs else 0f
    return (
        abs(offsetX) > SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX &&
            velocity > SWIPE_HORIZONTAL_VELOCITY_THRESHOLD_PX_PER_MS
    ) || abs(offsetX) > SWIPE_HORIZONTAL_AUTO_THRESHOLD_PX
}

/**
 * Returns the [MiniPlayerSwipeAction] that should be performed for a completed
 * gesture.  All parameters are in raw pixel deltas accumulated over the gesture.
 *
 * @param totalX        Net horizontal pixel displacement (positive = right).
 */
internal fun resolveMiniPlayerSwipeAction(
    totalX: Float,
): MiniPlayerSwipeAction {
    return when {
        totalX < -SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX -> MiniPlayerSwipeAction.SeekNext
        totalX > SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX  -> MiniPlayerSwipeAction.SeekPrev
        else                                          -> MiniPlayerSwipeAction.SnapBack
    }
}

internal enum class MiniPlayerSwipeAction {
    SeekNext,
    SeekPrev,
    SnapBack,
}

internal enum class MiniPlayerVerticalAction {
    Expand,
    SnapBack,
}

/** Decides which shared player-surface anchor wins after a vertical drag. */
internal fun settlePlayerSurfaceProgress(
    progress: Float,
    velocityYpxPerMs: Float,
): MiniPlayerVerticalAction {
    return if (
        velocityYpxPerMs <= -PLAYER_SURFACE_FLING_VELOCITY_PX_PER_MS ||
        (velocityYpxPerMs < PLAYER_SURFACE_FLING_VELOCITY_PX_PER_MS && progress >= 0.5f)
    ) {
        MiniPlayerVerticalAction.Expand
    } else {
        MiniPlayerVerticalAction.SnapBack
    }
}
