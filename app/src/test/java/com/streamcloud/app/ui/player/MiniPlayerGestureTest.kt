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
    fun swipeDownBeyondThresholdReturnsWithoutAction() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = 220f,
        )
        assertEquals(MiniPlayerSwipeAction.None, action)
    }

    @Test
    fun mediumDownwardSwipeReturnsWithoutAction() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = 80f,
        )
        assertEquals(MiniPlayerSwipeAction.None, action)
    }

    @Test
    fun verticalSwipeAtRestReturnsNone() {
        val action = resolveMiniPlayerSwipeAction(
            dirLocked = true,
            isHorizontal = false,
            totalX = 0f,
            totalY = 0f,
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
