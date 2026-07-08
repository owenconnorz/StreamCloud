package com.streamcloud.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerVisibilityTest {

    @Test
    fun hidesMiniPlayerAcrossSettingsAreaRoutes() {
        assertFalse(shouldShowGlobalMiniPlayer(SETTINGS_ROUTE, isMediaRoute = false))
        assertFalse(shouldShowGlobalMiniPlayer(SETTINGS_PLUGINS_ROUTE, isMediaRoute = false))
        assertFalse(shouldShowGlobalMiniPlayer(SETTINGS_COLLECTIONS_ROUTE, isMediaRoute = false))
    }

    @Test
    fun keepsMiniPlayerVisibleOnNonSettingsRoutes() {
        assertTrue(shouldShowGlobalMiniPlayer("movies", isMediaRoute = false))
        assertTrue(shouldShowGlobalMiniPlayer("music", isMediaRoute = false))
    }

    @Test
    fun hidesMiniPlayerOnMediaAndUnknownRoutesWithoutBackStackEntry() {
        assertFalse(shouldShowGlobalMiniPlayer("player/movie/test/title", isMediaRoute = true))
        assertFalse(shouldShowGlobalMiniPlayer(null, isMediaRoute = false))
    }
}
