package com.aioweb.app.data.nuvio

import kotlinx.serialization.Serializable

/**
 * Nuvio "provider" repository protocol — see
 *   https://github.com/yoruix/nuvio-providers/blob/template/DOCUMENTATION.md
 *
 * A repository is a `manifest.json` listing each available provider .js file.
 * We pull the file URL straight from `download_url` (or `url`) and execute it
 * against our Rhino runtime when resolving streams for a TMDB id.
 */
@Serializable
data class NuvioRepoManifest(
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val providers: List<NuvioProviderEntry> = emptyList(),
    /** D3adlyRocket / Nuvio-team repos use `scrapers` instead of `providers`. */
    val scrapers: List<NuvioProviderEntry> = emptyList(),
) {
    /** Unified accessor — newer repos use `scrapers`, older ones `providers`. */
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
    /** Either an absolute URL or a path relative to the repo's manifest.json. */
    @kotlinx.serialization.SerialName("downloadUrl") val downloadUrl: String? = null,
    @kotlinx.serialization.SerialName("download_url") val downloadUrlSnake: String? = null,
    val url: String? = null,
    /** D3adlyRocket / Nuvio-team repos: relative path like `providers/foo.js`. */
    val filename: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class InstalledNuvioProvider(
    val id: String,
    val name: String,
    val repoUrl: String,
    /** Absolute URL the .js file was originally downloaded from. */
    val downloadUrl: String,
    /** Local path to the cached .js bytes (so we don't re-download on every play). */
    val filePath: String,
    val installedAt: Long,
    val logo: String? = null,
    val description: String? = null,
)

/** Output of a single provider invocation — mirrors Nuvio's documented shape. */
@Serializable
data class NuvioStream(
    val name: String? = null,
    val title: String? = null,
    val url: String,
    val quality: String? = null,
    val headers: Map<String, String>? = null,
)
