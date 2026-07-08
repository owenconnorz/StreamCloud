package com.streamcloud.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaPermissionsTest {
    @Test
    fun usesGranularPermissionsOnAndroid13Plus() {
        assertEquals(
            listOf(LocalMediaPermissions.READ_MEDIA_AUDIO),
            LocalMediaPermissions.requiredPermissions(LocalMediaSection.Music, sdkInt = 33),
        )
        assertEquals(
            listOf(LocalMediaPermissions.READ_MEDIA_VIDEO),
            LocalMediaPermissions.requiredPermissions(LocalMediaSection.Videos, sdkInt = 34),
        )
        assertEquals(
            listOf(LocalMediaPermissions.READ_MEDIA_IMAGES),
            LocalMediaPermissions.requiredPermissions(LocalMediaSection.Images, sdkInt = 35),
        )
    }

    @Test
    fun usesLegacyStoragePermissionBeforeAndroid13() {
        LocalMediaSection.entries.forEach { section ->
            assertEquals(
                listOf(LocalMediaPermissions.READ_EXTERNAL_STORAGE),
                LocalMediaPermissions.requiredPermissions(section, sdkInt = 32),
            )
        }
    }
}
