package com.streamcloud.app.data

import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.stremio.InstalledStremioAddon

data class StreamProviderPriorityEntry(
    val key: String,
    val name: String,
    val kindLabel: String,
)

fun buildStreamProviderPriorityEntries(
    addons: List<InstalledStremioAddon>,
    nuvioProviders: List<InstalledNuvioProvider>,
    plugins: List<InstalledPlugin>,
): List<StreamProviderPriorityEntry> = buildList {
    addons.forEach { addon ->
        add(StreamProviderPriorityEntry("stremio:${addon.id}", addon.name, "Stremio add-on"))
    }
    nuvioProviders.forEach { provider ->
        add(StreamProviderPriorityEntry("nuvio:${provider.id}", provider.name, "Nuvio provider"))
    }
    plugins.forEach { plugin ->
        add(StreamProviderPriorityEntry("cs:${plugin.internalName}", plugin.name, "CloudStream plugin"))
    }
}.distinctBy { it.key }

fun orderStreamProviderEntries(
    entries: List<StreamProviderPriorityEntry>,
    savedOrder: List<String>,
): List<StreamProviderPriorityEntry> {
    val savedRanks = savedOrder.withIndex().associate { it.value to it.index }
    return entries.withIndex()
        .sortedWith(
            compareBy<IndexedValue<StreamProviderPriorityEntry>> {
                savedRanks[it.value.key] ?: Int.MAX_VALUE
            }.thenBy { it.index },
        )
        .map { it.value }
}

fun prioritizeStreamPickerTabs(
    tabs: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val (cloudStreamTabs, otherTabs) = tabs.partition { (key, _) -> key.startsWith("cs:") }
    return cloudStreamTabs + otherTabs
}

fun shouldKeepStreamPickerTab(
    isLoading: Boolean,
    hasStreams: Boolean,
    emptyResultConfirmed: Boolean,
): Boolean = isLoading || hasStreams || !emptyResultConfirmed

fun streamPickerTabLabel(key: String, name: String): String =
    if (key.startsWith("cs:")) "CloudStream · $name" else name

fun <T> firstReadyProviderItem(
    providerOrder: List<String>,
    itemsByProvider: Map<String, List<T>>,
    loadingProviders: Set<String>,
): T? {
    for (providerKey in providerOrder) {
        // A preferred provider may already have emitted a usable CloudStream link while
        // its remaining extractors are still resolving. Start with that link immediately.
        itemsByProvider[providerKey]?.firstOrNull()?.let { return it }
        // Do not fall back to a lower-priority provider before this one produces a link
        // or finishes empty.
        if (providerKey in loadingProviders) return null
    }
    return null
}

fun shouldAutoPlayBestStream(
    autoPlayEnabled: Boolean,
    isDownloadRequest: Boolean,
): Boolean = autoPlayEnabled && !isDownloadRequest

fun shouldShowAutoPlayResolvingState(
    autoPlayEnabled: Boolean,
    isLoading: Boolean,
    hasSources: Boolean,
): Boolean = autoPlayEnabled && (isLoading || hasSources)
