package com.streamcloud.app.data.ytmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Selects one audio format from the formats returned by the YouTube player endpoint. */
internal object AudioQualitySelector {
    fun normalize(value: String): String = when (value.trim().lowercase()) {
        "low", "medium", "high", "ultra" -> value.trim().lowercase()
        else -> "high"
    }

    fun select(formats: List<JsonObject>, requestedQuality: String): JsonObject? {
        if (formats.isEmpty()) return null
        val ordered = formats.sortedWith(formatComparator)
        val quality = normalize(requestedQuality)
        return when (quality) {
            "low" -> formats
                .filter { it.audioQuality() == "AUDIO_QUALITY_LOW" }
                .minWithOrNull(lowBitrateComparator)
                ?: ordered.first()
            "medium" -> formats
                .filter { it.audioQuality() == "AUDIO_QUALITY_MEDIUM" }
                .maxWithOrNull(formatComparator)
                ?: ordered[ordered.lastIndex / 2]
            "high" -> formats
                .filter { it.audioQuality() == "AUDIO_QUALITY_HIGH" }
                .maxWithOrNull(formatComparator)
                ?: ordered.last()
            "ultra" -> ordered.last()
            else -> ordered.last()
        }
    }

    private val formatComparator = compareBy<JsonObject> { it.bitrate() }
        .thenBy { if (it.prefersOpus()) 1 else 0 }
        .thenBy { if (it.isPreferredOpusItag()) 1 else 0 }

    private val lowBitrateComparator = compareBy<JsonObject> { it.bitrate() }
        .thenByDescending { if (it.prefersOpus()) 1 else 0 }
        .thenByDescending { if (it.isPreferredOpusItag()) 1 else 0 }

    private fun JsonObject.audioQuality(): String? =
        this["audioQuality"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bitrate(): Long =
        this["bitrate"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    private fun JsonObject.prefersOpus(): Boolean =
        this["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("opus", ignoreCase = true) ||
            this["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty().startsWith("audio/webm", ignoreCase = true)

    private fun JsonObject.isPreferredOpusItag(): Boolean =
        this["itag"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 774
}
