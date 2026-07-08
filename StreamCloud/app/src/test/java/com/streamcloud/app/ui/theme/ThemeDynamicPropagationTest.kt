package com.streamcloud.app.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDynamicPropagationTest {

    @Test
    fun albumArtThemeAppliesWhenMiniThemeEnabledAndArtworkPresent() {
        assertTrue(
            shouldUseAlbumArtDynamicTheme(
                dynamicColorEnabled = false,
                dynamicMiniPlayerThemeEnabled = true,
                hasArtwork = true,
            ),
        )
    }

    @Test
    fun albumArtThemeDoesNotApplyWithoutArtwork() {
        assertFalse(
            shouldUseAlbumArtDynamicTheme(
                dynamicColorEnabled = true,
                dynamicMiniPlayerThemeEnabled = true,
                hasArtwork = false,
            ),
        )
    }

    @Test
    fun systemDynamicThemeRequiresGlobalDynamicSetting() {
        assertFalse(
            shouldUseSystemDynamicTheme(
                dynamicColorEnabled = false,
                supportsDynamic = true,
            ),
        )
        assertTrue(
            shouldUseSystemDynamicTheme(
                dynamicColorEnabled = true,
                supportsDynamic = true,
            ),
        )
    }

    @Test
    fun albumArtThemeAppliesWhenOnlyDynamicColorEnabledAndArtworkPresent() {
        assertTrue(
            shouldUseAlbumArtDynamicTheme(
                dynamicColorEnabled = true,
                dynamicMiniPlayerThemeEnabled = false,
                hasArtwork = true,
            ),
        )
    }

    @Test
    fun albumArtThemeDoesNotApplyWhenBothDisabledEvenWithArtwork() {
        assertFalse(
            shouldUseAlbumArtDynamicTheme(
                dynamicColorEnabled = false,
                dynamicMiniPlayerThemeEnabled = false,
                hasArtwork = true,
            ),
        )
    }

    @Test
    fun systemDynamicThemeDoesNotApplyWhenDeviceDoesNotSupportDynamic() {
        assertFalse(
            shouldUseSystemDynamicTheme(
                dynamicColorEnabled = true,
                supportsDynamic = false,
            ),
        )
    }
}

