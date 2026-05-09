package com.aioweb.app.data.stremio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Stremio addon protocol — minimal subset needed to render catalogs,
 * meta details, and stream lists in StreamCloud.
 *
 * Spec: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
 */
@Serializable
data class StremioManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    val resources: List<JsonElement> = emptyList(),       // can be ["catalog","stream"] OR rich objects
    val types: List<String> = emptyList(),
    val catalogs: List<StremioCatalogDef> = emptyList(),
    val idPrefixes: List<String>? = null,
)

@Serializable
data class StremioCatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<StremioExtra>? = null,
)

@Serializable
data class StremioExtra(
    val name: String,
    @SerialName("isRequired") val isRequired: Boolean = false,
    val options: List<String>? = null,
    val optionsLimit: Int? = null,
)

@Serializable
data class StremioMetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val description: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val runtime: String? = null,
)

@Serializable
data class StremioCatalogResponse(
    val metas: List<StremioMetaPreview> = emptyList(),
)

@Serializable
data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    @SerialName("infoHash") val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: StremioStreamHints? = null,
    val sources: List<String>? = null,
)

@Serializable
data class StremioStreamHints(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream> = emptyList(),
)

@Serializable
data class StremioMetaResponse(
    val meta: StremioMeta? = null,
)

@Serializable
data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val runtime: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val videos: List<StremioVideo>? = null,
)

@Serializable
data class StremioVideo(
    val id: String,
    val title: String? = null,
    val released: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
)

@Serializable
data class InstalledStremioAddon(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val baseUrl: String,             // manifestUrl with /manifest.json stripped
    val logo: String? = null,
    val installedAt: Long,
)

/**
 * One horizontal row on the Movies home page sourced from a Stremio addon
 * catalog. Mirrors NuvioMobile's `HomeCatalogSection` minus the disk-cache
 * machinery — we re-fetch on every `loadDiscover()` for now.
 */
data class StremioHomeRow(
    val addonId: String,
    val addonName: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,                // "movie" | "series" | "channel" | …
    val items: List<StremioMetaPreview>,
) {
    val rowKey: String get() = "$addonId:$type:$catalogId"
}
