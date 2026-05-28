package com.streamcloud.app.data.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PinnedCsSection(
    val pluginInternalName: String,
    val pluginDisplayName: String,
    val sectionName: String,
)

internal val csHomeSectionsJson = Json { ignoreUnknownKeys = true }
