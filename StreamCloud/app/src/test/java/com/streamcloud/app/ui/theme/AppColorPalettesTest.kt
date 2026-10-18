package com.streamcloud.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppColorPalettesTest {

    @Test
    fun palettesMatchTheReferenceSwatches() {
        val expected = mapOf(
            "modern_blue" to listOf(
                Color(0xFF202B3B), Color(0xFF29384D), Color(0xFF34445A),
                Color(0xFF4F8EF7), Color.White, Color(0xFFB8C4D6),
            ),
            "purple_aurora" to listOf(
                Color(0xFF2A1F4B), Color(0xFF3B2A6B), Color(0xFF5D3FD3),
                Color(0xFFA855F7), Color.White, Color(0xFFD4C6F1),
            ),
            "sunset_orange" to listOf(
                Color(0xFF3D1E0F), Color(0xFF6B3118), Color(0xFFF97316),
                Color(0xFFFDBA74), Color.White, Color(0xFFF3D7B8),
            ),
            "forest_green" to listOf(
                Color(0xFF0F2F2A), Color(0xFF1B4D43), Color(0xFF2DD4BF),
                Color(0xFF86EFAC), Color.White, Color(0xFFCFE8D9),
            ),
            "light_clean" to listOf(
                Color(0xFFE9EDF2), Color(0xFFD6E4F5), Color(0xFF93C5FD),
                Color(0xFF3B82F6), Color(0xFF1E293B), Color(0xFF64748B),
            ),
        )

        assertEquals(expected.keys, AllAppColorPalettes.map { it.id }.toSet())
        expected.forEach { (id, swatches) ->
            assertEquals(swatches, palettes[id]?.swatches)
        }
    }

    @Test
    fun darkSchemesUseAllSixReferenceColors() {
        AllAppColorPalettes.forEach { palette ->
            val scheme = appColorScheme(palette.id, light = false)
            assertEquals(palette.background, scheme.background)
            assertEquals(palette.surface, scheme.surface)
            assertEquals(palette.surfaceVariant, scheme.surfaceVariant)
            assertEquals(palette.primary, scheme.primary)
            assertEquals(palette.primary, scheme.secondary)
            assertEquals(palette.surfaceVariant, scheme.primaryContainer)
            assertEquals(palette.onSurface, scheme.onSurface)
            assertEquals(palette.onSurfaceVariant, scheme.onSurfaceVariant)
        }
    }

    @Test
    fun legacyScreensKeepTheirExistingPaletteColors() {
        val sunset = legacyAppColorScheme("sunset_orange", light = false)
        assertEquals(Color(0xFF6B3118), sunset.surfaceVariant)
        assertEquals(Color(0xFFF97316), sunset.primary)
        assertEquals(Color(0xFFFDBA74), sunset.primaryContainer)

        val forest = legacyAppColorScheme("forest_green", light = false)
        assertEquals(Color(0xFF1B4D43), forest.surfaceVariant)
        assertEquals(Color(0xFF2DD4BF), forest.primary)
        assertEquals(Color(0xFF86EFAC), forest.primaryContainer)
    }

    @Test
    fun legacyPalettePreferencesMapToTheNewSchemes() {
        assertEquals("modern_blue", normalizeAppColorPaletteId("ocean"))
        assertEquals("purple_aurora", normalizeAppColorPaletteId("violet"))
        assertEquals("sunset_orange", normalizeAppColorPaletteId("sunset"))
        assertEquals("forest_green", normalizeAppColorPaletteId("emerald"))
    }

    @Test
    fun lightCleanUsesTheReferenceLightSurfaceColors() {
        val scheme = appColorScheme("light_clean", light = true)

        assertNotNull(scheme)
        assertEquals(Color(0xFFE9EDF2), scheme.background)
        assertEquals(Color(0xFFD6E4F5), scheme.surface)
        assertEquals(Color(0xFF3B82F6), scheme.primary)
    }
}
