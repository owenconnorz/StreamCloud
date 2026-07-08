package com.streamcloud.app.data.local

enum class LocalMediaSection(val label: String) {
    Music("Music"),
    Videos("Videos"),
    Images("Images"),
}

object LocalMediaPermissions {
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"

    fun requiredPermissions(section: LocalMediaSection, sdkInt: Int): List<String> {
        if (sdkInt >= 33) {
            return when (section) {
                LocalMediaSection.Music -> listOf(READ_MEDIA_AUDIO)
                LocalMediaSection.Videos -> listOf(READ_MEDIA_VIDEO)
                LocalMediaSection.Images -> listOf(READ_MEDIA_IMAGES)
            }
        }
        return listOf(READ_EXTERNAL_STORAGE)
    }
}
