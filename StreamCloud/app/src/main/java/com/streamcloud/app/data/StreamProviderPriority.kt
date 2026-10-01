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

fun shouldAutoPlayBestStream(
    autoPlayEnabled: Boolean,
    isDownloadRequest: Boolean,
): Boolean = autoPlayEnabled && !isDownloadRequest

fun shouldShowAutoPlayResolvingState(
    autoPlayEnabled: Boolean,
    isLoading: Boolean,
    hasSources: Boolean,
): Boolean = autoPlayEnabled && (isLoading || hasSources)
