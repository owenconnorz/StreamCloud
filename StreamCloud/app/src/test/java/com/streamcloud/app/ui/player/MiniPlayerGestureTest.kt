package com.streamcloud.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerGestureTest {

    // ── Direction-locked, horizontal ─────────────────────────────────────────

    @Test
    fun swipeLeftBeyondThresholdSeeksNext() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = true,
            totalX = -(SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX + 1f),
            totalY = 0f,
        )
        assertEquals(MiniPlayerSwipeAction.SeekNext, action)
    }

    @Test
    fun swipeRightBeyondThresholdSeeksPrev() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = true,
            totalX = SWIPE_HORIZONTAL_SEEK_THRESHOLD_PX + 1f,
            totalY = 0f,
        )
        assertEquals(MiniPlayerSwipeAction.SeekPrev, action)
    }

    @Test
    fun shortHorizontalSwipeSnapsBack() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = true,
            totalX = 20f,
            totalY = 0f,
        )
        assertEquals(MiniPlayerSwipeAction.SnapBack, action)
    }

    // ── Direction-locked, vertical ───────────────────────────────────────────

    @Test
    fun swipeUpBeyondThresholdExpands() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = -(SWIPE_UP_EXPAND_THRESHOLD_PX + 1f),
        )
        assertEquals(MiniPlayerSwipeAction.Expand, action)
    }

    @Test
    fun swipeDownBeyondThresholdDismisses() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = SWIPE_DOWN_DISMISS_THRESHOLD_PX + 1f,
        )
        assertEquals(MiniPlayerSwipeAction.Dismiss, action)
    }

    @Test
    fun swipeDownBelowThresholdDoesNotDismiss() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = SWIPE_DOWN_DISMISS_THRESHOLD_PX - 1f,
        )
        assertEquals(MiniPlayerSwipeAction.None, action)
    }

    @Test
    fun exactThresholdBoundaryDoesNotDismiss() {
        // Boundary is exclusive: must be strictly greater than threshold
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = SWIPE_DOWN_DISMISS_THRESHOLD_PX,
        )
        assertEquals(MiniPlayerSwipeAction.None, action)
    }

    // ── No direction locked ──────────────────────────────────────────────────

    @Test
    fun unlockNodirReturnsNone() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = false,
            isHorizontal = false,
            totalX = 0f,
            totalY = 200f,
        )
        assertEquals(MiniPlayerSwipeAction.None, action)
    }
}
