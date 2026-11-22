package com.streamcloud.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TvNavigationFocusPolicyTest {
    @Test
    fun destinationPillTracksRemoteFocusOnlyWhileOnNavigation() {
        assertEquals(
            2,
            resolveTvNavigationFocusIndex(
                navHasFocus = true,
                searchFocused = false,
                profileFocused = false,
                destinationHasFocus = true,
                focusedDestinationIndex = 2,
            ),
        )
        assertEquals(
            -1,
            resolveTvNavigationFocusIndex(
                navHasFocus = false,
                searchFocused = false,
                profileFocused = false,
                destinationHasFocus = false,
                focusedDestinationIndex = 2,
            ),
        )
        assertEquals(
            -1,
            resolveTvNavigationFocusIndex(
                navHasFocus = true,
                searchFocused = true,
                profileFocused = false,
                destinationHasFocus = false,
                focusedDestinationIndex = 2,
            ),
        )
        assertEquals(
            -1,
            resolveTvNavigationFocusIndex(
                navHasFocus = true,
                searchFocused = false,
                profileFocused = true,
                destinationHasFocus = false,
                focusedDestinationIndex = 2,
            ),
        )
        assertEquals(
            -1,
            resolveTvNavigationFocusIndex(
                navHasFocus = true,
                searchFocused = false,
                profileFocused = false,
                destinationHasFocus = false,
                focusedDestinationIndex = 2,
            ),
        )
    }
}
