package com.streamcloud.app.audio

internal fun androidAutoQueueStartIndex(
    queueMediaIds: List<String>,
    selectedMediaId: String,
    preferredIndex: Int?,
): Int? {
    if (preferredIndex != null && queueMediaIds.getOrNull(preferredIndex) == selectedMediaId) {
        return preferredIndex
    }
    return queueMediaIds.indexOf(selectedMediaId).takeIf { it >= 0 }
}

internal fun <T> androidAutoQueueStartingAt(items: List<T>, startIndex: Int): List<T> {
    if (items.isEmpty()) return emptyList()
    val safeIndex = startIndex.coerceIn(0, items.lastIndex)
    return items.drop(safeIndex) + items.take(safeIndex)
}
