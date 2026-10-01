package com.streamcloud.app.audio

internal object AndroidAutoLikedContent {
    private val youtubeVideoIdPattern =
        Regex("""(?:[?&]v=|youtu\.be/|/embed/)([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE)

    fun identity(mediaId: String, videoId: String? = null): String {
        val stableVideoId = videoId
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?: youtubeVideoIdPattern.find(mediaId)?.groupValues?.getOrNull(1)
        return stableVideoId?.let { "youtube:$it" } ?: "media:$mediaId"
    }

    fun <T> mergePreferFirst(
        preferred: List<T>,
        fallback: List<T>,
        identity: (T) -> String,
    ): List<T> = (preferred + fallback).distinctBy(identity)
}