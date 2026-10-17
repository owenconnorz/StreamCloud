package com.streamcloud.app.ui.theme

import org.junit.Assert.*
import org.junit.Test

class TvFocusRestorePolicyTest {
    @Test fun remembersTheChildOnExit() {
        var saves = 0
        val policy = TvFocusRestorePolicy({ saves++; true }, { true })
        policy.onExit()
        assertEquals(1, saves)
        assertTrue(policy.onEnter())
    }

    @Test fun removedChildUsesDefaultFocusInsteadOfCancellingEntry() {
        val policy = TvFocusRestorePolicy({ true }, { false })
        assertFalse(policy.onEnter())
    }

    @Test fun reentrantEntryDoesNotRestoreOrReleaseTheSameItemTwice() {
        var restores = 0
        lateinit var policy: TvFocusRestorePolicy
        policy = TvFocusRestorePolicy({ true }, {
            restores++
            assertFalse(policy.onEnter())
            true
        })
        assertTrue(policy.onEnter())
        assertEquals(1, restores)
    }

    @Test fun exitDuringRestorationDoesNotOverwriteSavedFocus() {
        var saves = 0
        lateinit var policy: TvFocusRestorePolicy
        policy = TvFocusRestorePolicy({ saves++; true }, {
            policy.onExit()
            true
        })
        assertTrue(policy.onEnter())
        assertEquals(0, saves)
        policy.onExit()
        assertEquals(1, saves)
    }

    @Test fun failedRestorationCanBeRetriedAfterContentChanges() {
        var attached = false
        val policy = TvFocusRestorePolicy({ true }, { attached })
        assertFalse(policy.onEnter())
        attached = true
        assertTrue(policy.onEnter())
        assertTrue(policy.onEnter())
    }

    @Test fun thrownFailureDoesNotLeaveReentryGuardStuck() {
        var attempts = 0
        val policy = TvFocusRestorePolicy({ true }, {
            attempts++
            if (attempts == 1) error("test failure")
            true
        })
        assertTrue(runCatching { policy.onEnter() }.isFailure)
        assertTrue(policy.onEnter())
        assertEquals(2, attempts)
    }
}
