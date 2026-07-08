package com.streamcloud.app.ui.player

/**
 * Threshold (in pixels) a downward swipe must exceed before the mini-player is
 * stopped and dismissed. Kept intentionally generous to avoid accidental triggers
 * from minor drags.
 */
internal const val SWIPE_DOWN_DISMISS_THRESHOLD_PX = 80f

/**
 * Threshold (in pixels) an upward swipe must exceed to expand the full player.
 */
internal const val SWIPE_UP_EXPAND_THRESHOLD_PX = 60f

/**
 * Threshold (in pixels) a horizontal swipe must exceed to seek next/previous.
 */
internal const val SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX = 80f

/**
 * Returns the [MiniPlayerSwipeAction] that should be performed for a completed
 * gesture.  All parameters are in raw pixel deltas accumulated over the gesture.
 *
 * @param dirLocked     Whether a primary direction (horizontal / vertical) has been
 *                      established for this gesture.
 * @param isHorizontal  True when the gesture direction was locked to the X axis.
 * @param totalX        Net horizontal pixel displacement (positive = right).
 * @param totalY        Net vertical pixel displacement (positive = down).
 */
internal fun resolveMiniPlayerSwipeAction(
    dirLocked: Boolean,
    isHorizontal: Boolean,
    totalX: Float,
    totalY: Float,
): MiniPlayerSwipeAction {
    if (!dirLocked) return MiniPlayerSwipeAction.None
    return when {
        isHorizontal && totalX < -SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX -> MiniPlayerSwipeAction.SeekNext
        isHorizontal && totalX > SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX  -> MiniPlayerSwipeAction.SeekPrev
        isHorizontal                                                  -> MiniPlayerSwipeAction.SnapBack
        !isHorizontal && totalY < -SWIPE_UP_EXPAND_THRESHOLD_PX      -> MiniPlayerSwipeAction.Expand
        !isHorizontal && totalY > SWIPE_DOWN_DISMISS_THRESHOLD_PX    -> MiniPlayerSwipeAction.Dismiss
        else                                                          -> MiniPlayerSwipeAction.None
    }
}

internal enum class MiniPlayerSwipeAction {
    SeekNext,
    SeekPrev,
    SnapBack,
    Expand,
    /** Stop playback and hide the mini-player. */
    Dismiss,
    None,
}
