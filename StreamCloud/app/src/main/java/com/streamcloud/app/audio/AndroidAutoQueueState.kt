package com.streamcloud.app.audio

internal data class AndroidAutoQueueWindow(
    val startIndex: Int,
    val endExclusive: Int,
    val currentIndex: Int,
)

/**
 * Keeps the active track in a bounded snapshot, with room for a small amount of queue history.
 * Any remaining capacity is used for tracks after the active item.
 */
internal fun androidAutoQueueWindow(
    itemCount: Int,
    currentIndex: Int,
    maxItems: Int,
): AndroidAutoQueueWindow? {
    if (itemCount <= 0 || maxItems <= 0) return null
    val count = minOf(itemCount, maxItems)
    val activeIndex = currentIndex.coerceIn(0, itemCount - 1)
    val historyAllowance = minOf(activeIndex, maxItems / 4)
    var startIndex = activeIndex - historyAllowance
    if (startIndex + count > itemCount) startIndex = itemCount - count
    val endExclusive = startIndex + count
    return AndroidAutoQueueWindow(
        startIndex = startIndex,
        endExclusive = endExclusive,
        currentIndex = activeIndex - startIndex,
    )
}

internal fun canPersistLocalPlaybackUri(uri: String?): Boolean {
    val scheme = uri
        ?.substringBefore(':', missingDelimiterValue = "")
        ?.lowercase()
    return scheme == "file" || scheme == "content" || scheme == "android.resource"
}

internal fun normalizedRepeatMode(mode: Int): Int = when (mode) {
    androidx.media3.common.Player.REPEAT_MODE_ONE,
    androidx.media3.common.Player.REPEAT_MODE_ALL,
    -> mode
    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
}