package com.streamcloud.app.ui.screens

internal fun selectedMusicVideoId(
    isMusicVideo: Boolean,
    explicitVideoId: String,
    mediaId: String?,
): String {
    if (!isMusicVideo) return ""
    if (explicitVideoId.isNotBlank()) return explicitVideoId
    val mid = mediaId ?: return ""
    return if (mid.startsWith("http")) {
        mid.substringAfter("v=", "").substringBefore("&").takeIf { it.isNotBlank() } ?: ""
    } else {
        mid
    }
}

internal fun selectedMusicVideoWatchUrl(
    explicitWatchUrl: String,
    mediaId: String?,
    videoId: String,
): String = when {
    explicitWatchUrl.isNotBlank() -> explicitWatchUrl
    mediaId?.startsWith("http") == true -> mediaId
    videoId.isNotBlank() -> "https://music.youtube.com/watch?v=$videoId"
    else -> ""
}
