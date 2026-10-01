package com.streamcloud.app.audio

internal enum class AndroidAutoSearchState {
    RESULTS,
    NO_MATCHES,
    PROVIDERS_UNAVAILABLE,
}

internal data class AndroidAutoSearchOutcome<T>(
    val items: List<T>,
    val state: AndroidAutoSearchState,
) {
    val browserResultCount: Int
        get() = when (state) {
            AndroidAutoSearchState.PROVIDERS_UNAVAILABLE -> 1
            AndroidAutoSearchState.NO_MATCHES -> 0
            AndroidAutoSearchState.RESULTS -> items.size
        }
}

internal fun <T> androidAutoSearchOutcome(
    items: List<T>,
    anyProviderSucceeded: Boolean,
): AndroidAutoSearchOutcome<T> = when {
    items.isNotEmpty() -> AndroidAutoSearchOutcome(items, AndroidAutoSearchState.RESULTS)
    anyProviderSucceeded -> AndroidAutoSearchOutcome(emptyList(), AndroidAutoSearchState.NO_MATCHES)
    else -> AndroidAutoSearchOutcome(emptyList(), AndroidAutoSearchState.PROVIDERS_UNAVAILABLE)
}

internal fun androidAutoSearchMessage(
    state: AndroidAutoSearchState,
    query: String,
): String = when (state) {
    AndroidAutoSearchState.PROVIDERS_UNAVAILABLE ->
        "Search is unavailable. Check your connection and try again."
    AndroidAutoSearchState.NO_MATCHES ->
        "No songs found for “${query.trim()}”."
    AndroidAutoSearchState.RESULTS -> ""
}