package com.streamcloud.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerGestureTest {

    @Test
    fun dragAxisWaitsUntilTouchSlop() {
        assertEquals(
            MiniPlayerDragAxis.Undecided,
            resolveMiniPlayerDragAxis(totalX = 3f, totalY = -7f, touchSlop = 8f),
        )
    }

    @Test
    fun mostlyVerticalDragLocksToPlayerExpansion() {
        assertEquals(
            MiniPlayerDragAxis.Vertical,
            resolveMiniPlayerDragAxis(totalX = 4f, totalY = -12f, touchSlop = 8f),
        )
    }

    @Test
    fun mostlyHorizontalDragLocksToTrackSwipe() {
        assertEquals(
            MiniPlayerDragAxis.Horizontal,
            resolveMiniPlayerDragAxis(totalX = -12f, totalY = 4f, touchSlop = 8f),
        )
    }

    @Test
    fun sharedSurfacePastHalfSettlesExpanded() {
        assertEquals(
            MiniPlayerVerticalAction.Expand,
            settlePlayerSurfaceProgress(progress = 0.51f, velocityYpxPerMs = 0f),
        )
    }

    @Test
    fun upwardFlingSettlesExpandedBeforeHalfway() {
        assertEquals(
            MiniPlayerVerticalAction.Expand,
            settlePlayerSurfaceProgress(
                progress = 0.2f,
                velocityYpxPerMs = -(PLAYER_SURFACE_FLING_VELOCITY_PX_PER_MS + 0.1f),
            ),
        )
    }

    @Test
    fun slowReleaseBeforeHalfSettlesAtMiniAnchor() {
        assertEquals(
            MiniPlayerVerticalAction.SnapBack,
            settlePlayerSurfaceProgress(progress = 0.49f, velocityYpxPerMs = 0f),
        )
    }

    @Test
    fun downwardFlingSettlesAtMiniAnchor() {
        assertEquals(
            MiniPlayerVerticalAction.SnapBack,
            settlePlayerSurfaceProgress(progress = 0.9f, velocityYpxPerMs = 2f),
        )
    }

    @Test
    fun swipeLeftBeyondThresholdSeeksNext() {
        val action = resolveMiniPlayerSwipeAction(
            totalX = -(SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX + 1f),
        )
        assertEquals(MiniPlayerSwipeAction.SeekNext, action)
    }

    @Test
    fun swipeRightBeyondThresholdSeeksPrev() {
        val action = resolveMiniPlayerSwipeAction(
            totalX = SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX + 1f,
        )
        assertEquals(MiniPlayerSwipeAction.SeekPrev, action)
    }

    @Test
    fun shortHorizontalSwipeSnapsBack() {
        val action = resolveMiniPlayerSwipeAction(
            totalX = 20f,
        )
        assertEquals(MiniPlayerSwipeAction.SnapBack, action)
    }

    @Test
    fun exactHorizontalThresholdSnapsBack() {
        val action = resolveMiniPlayerSwipeAction(
            totalX = SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX,
        )
        assertEquals(MiniPlayerSwipeAction.SnapBack, action)
    }

    @Test
    fun zeroHorizontalMovementSnapsBack() {
        val action = resolveMiniPlayerSwipeAction(
            totalX = 0f,
        )
        assertEquals(MiniPlayerSwipeAction.SnapBack, action)
    }

    @Test
    fun fastSwipePastMinimumDistanceChangesTrack() {
        assertEquals(
            true,
            shouldCommitMiniPlayerTrackSwipe(
                offsetX = SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX + 10f,
                totalDistance = 120f,
                durationMs = 40L,
                canChangeSong = true,
            ),
        )
    }

    @Test
    fun slowShortSwipeSnapsBack() {
        assertEquals(
            false,
            shouldCommitMiniPlayerTrackSwipe(
                offsetX = SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX + 10f,
                totalDistance = 60f,
                durationMs = 500L,
                canChangeSong = true,
            ),
        )
    }

    @Test
    fun deliberateLongDragChangesTrackEvenWhenSlow() {
        assertEquals(
            true,
            shouldCommitMiniPlayerTrackSwipe(
                offsetX = SWIPE_HORIZONTAL_AUTO_THRESHOLD_PX + 1f,
                totalDistance = SWIPE_HORIZONTAL_AUTO_THRESHOLD_PX + 1f,
                durationMs = 1_000L,
                canChangeSong = true,
            ),
        )
    }

    @Test
    fun unavailableQueueDirectionAlwaysSnapsBack() {
        assertEquals(
            false,
            shouldCommitMiniPlayerTrackSwipe(
                offsetX = SWIPE_HORIZONTAL_AUTO_THRESHOLD_PX + 50f,
                totalDistance = SWIPE_HORIZONTAL_AUTO_THRESHOLD_PX + 50f,
                durationMs = 50L,
                canChangeSong = false,
            ),
        )
    }
}
