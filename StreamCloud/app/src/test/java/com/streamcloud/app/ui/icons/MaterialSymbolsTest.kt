package com.streamcloud.app.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.*
import org.junit.Test

class MaterialSymbolsTest {
    @Test fun everyBundledVariantBuildsAVisibleNativeVector() {
        var count = 0
        listOf(AppIcons.Filled, AppIcons.Outlined).forEach { style ->
            style.javaClass.declaredMethods
                .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
                .forEach { getter ->
                    val vector = getter.invoke(style) as ImageVector
                    assertEquals(getter.name, 24f, vector.defaultWidth.value, 0f)
                    assertEquals(getter.name, 24f, vector.defaultHeight.value, 0f)
                    assertTrue(getter.name, vector.viewportWidth > 0)
                    assertTrue(getter.name, vector.viewportHeight > 0)
                    val translated = vector.root[0] as VectorGroup
                    assertTrue(getter.name, translated.size > 0)
                    for (index in 0 until translated.size) {
                        assertTrue(getter.name, (translated[index] as VectorPath).pathData.isNotEmpty())
                    }
                    count++
                }
        }
        assertEquals(302, count)
    }

    @Test fun borderAliasesKeepTheirUnselectedMeaning() {
        assertSame(AppIcons.Outlined.Favorite, AppIcons.Filled.FavoriteBorder)
        assertSame(AppIcons.Outlined.Bookmark, AppIcons.Filled.BookmarkBorder)
        assertNotSame(AppIcons.Filled.Favorite, AppIcons.Filled.FavoriteBorder)
        assertNotSame(AppIcons.Filled.Bookmark, AppIcons.Filled.BookmarkBorder)
    }

    @Test fun legacyDirectionAwareControlsStillMirrorInRtl() {
        assertTrue(AppIcons.AutoMirrored.Filled.ArrowBack.autoMirror)
        assertTrue(AppIcons.AutoMirrored.Filled.ArrowForward.autoMirror)
        assertTrue(AppIcons.AutoMirrored.Filled.PlaylistAdd.autoMirror)
        assertTrue(AppIcons.AutoMirrored.Filled.PlaylistPlay.autoMirror)
        assertTrue(AppIcons.AutoMirrored.Filled.Send.autoMirror)
        assertFalse(AppIcons.Filled.PlayArrow.autoMirror)
    }

    @Test fun bothCoordinateConventionsFitTheirViewports() {
        val fontCoordinates = AppIcons.Filled.Settings
        val originalCoordinates = AppIcons.Outlined.AutoAwesome
        assertEquals(960f, fontCoordinates.viewportHeight, 0f)
        assertEquals(960f, (fontCoordinates.root[0] as VectorGroup).translationY, 0f)
        assertEquals(24f, originalCoordinates.viewportHeight, 0f)
        assertEquals(0f, (originalCoordinates.root[0] as VectorGroup).translationY, 0f)
    }

    @Test fun repeatedlyUsedIconsReuseTheirParsedVectors() {
        assertSame(AppIcons.Filled.Settings, AppIcons.Default.Settings)
        assertSame(AppIcons.Filled.Cast, AppIcons.Filled.Cast)
        assertSame(AppIcons.Outlined.Theaters, AppIcons.Outlined.Theaters)
    }

    @Test fun navigationHasSeparateFilledAndOutlinedAssets() {
        val pairs = listOf(
            AppIcons.Filled.Theaters to AppIcons.Outlined.Theaters,
            AppIcons.Filled.MusicNote to AppIcons.Outlined.MusicNote,
            AppIcons.Filled.Bookmarks to AppIcons.Outlined.Bookmarks,
            AppIcons.Filled.Folder to AppIcons.Outlined.Folder,
            AppIcons.Filled.LiveTv to AppIcons.Outlined.LiveTv,
            AppIcons.Filled.Whatshot to AppIcons.Outlined.Whatshot,
            AppIcons.Filled.Settings to AppIcons.Outlined.Settings,
        )
        pairs.forEach { (filled, outlined) -> assertNotSame(filled, outlined) }
    }
}
