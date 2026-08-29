package com.streamcloud.app.ui.theme

import org.junit.Assert.assertEquals
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

    @Test
    fun neutralArtworkDoesNotInventAHighSaturationRedAccent() {
        val sample = AlbumArtThemeBus.AlbumArtColorSample(
            hue = 0f,
            saturation = 0f,
            lightness = 0.52f,
            population = 1_000,
        )

        val themed = AlbumArtThemeBus.themeHslForAlbumArt(sample)

        assertEquals(0f, themed[0], 0.001f)
        assertEquals(0f, themed[1], 0.001f)
    }

    @Test
    fun meaningfulArtworkColourBeatsAnecdotalTinyAccentPixel() {
        val selected = AlbumArtThemeBus.selectAlbumArtColorSample(
            listOf(
                AlbumArtThemeBus.AlbumArtColorSample(
                    hue = 0f,
                    saturation = 1f,
                    lightness = 0.55f,
                    population = 10,
                ),
                AlbumArtThemeBus.AlbumArtColorSample(
                    hue = 215f,
                    saturation = 0.48f,
                    lightness = 0.32f,
                    population = 990,
                ),
            ),
        )

        val sample = requireNotNull(selected)
        assertEquals(215f, sample.hue, 0.001f)
    }

    @Test
    fun meaningfulChromaticColourIsSelectedFromMostlyNeutralArtwork() {
        val selected = AlbumArtThemeBus.selectAlbumArtColorSample(
            listOf(
                AlbumArtThemeBus.AlbumArtColorSample(
                    hue = 0f,
                    saturation = 0.02f,
                    lightness = 0.42f,
                    population = 700,
                ),
                AlbumArtThemeBus.AlbumArtColorSample(
                    hue = 205f,
                    saturation = 0.62f,
                    lightness = 0.38f,
                    population = 300,
                ),
            ),
        )

        val sample = requireNotNull(selected)
        assertEquals(205f, sample.hue, 0.001f)
    }
}

