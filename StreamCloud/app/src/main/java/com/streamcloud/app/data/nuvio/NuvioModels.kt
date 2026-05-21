package com.streamcloud.app.data.nuvio

import kotlinx.serialization.Serializable

@Serializable
data class NuvioRepoManifest(
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val providers: List<NuvioProviderEntry> = emptyList(),

    val scrapers: List<NuvioProviderEntry> = emptyList(),
) {

    val allProviders: List<NuvioProviderEntry> get() = providers + scrapers
}

@Serializable
data class NuvioProviderEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val author: String? = null,
    val logo: String? = null,
    val icon: String? = null,

    @kotlinx.serialization.SerialName("downloadUrl") val downloadUrl: String? = null,
    @kotlinx.serialization.SerialName("download_url") val downloadUrlSnake: String? = null,
    val url: String? = null,

    val filename: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class InstalledNuvioProvider(
    val id: String,
    val name: String,
    val repoUrl: String,

    val downloadUrl: String,

    val filePath: String,
    val installedAt: Long,
    val logo: String? = null,
    val description: String? = null,
)

@Serializable
data class NuvioStream(
    val name: String? = null,
    val title: String? = null,
    val url: String,
    val quality: String? = null,
    val headers: Map<String, String>? = null,
)
