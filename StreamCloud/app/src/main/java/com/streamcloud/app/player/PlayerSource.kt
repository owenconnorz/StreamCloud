package com.streamcloud.app.player

import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioStream

data class PlayerSource(
    val id: String,
    val url: String,
    val label: String,
    val addonName: String,
    val qualityTag: String? = null,
    val isMagnet: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

// Shared conversion — used from MovieDetailScreen, StremioDetailScreen, and
// StreamCloudApp so there is a single authoritative NuvioStream → PlayerSource path.
fun NuvioStream.toPlayerSource(provider: InstalledNuvioProvider): PlayerSource {
    val label = title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: "Stream"
    return PlayerSource(
        id = "nuvio::${provider.id}::${url.hashCode()}::${label.hashCode()}",
        url = url,
        label = label,
        addonName = provider.name,
        qualityTag = normaliseNuvioQuality(quality),
        isMagnet = url.startsWith("magnet:"),
        headers = headers ?: emptyMap(),
    )
}

fun normaliseNuvioQuality(q: String?): String? {
    if (q.isNullOrBlank()) return null
    val s = q.trim()
    return when {
        s.equals("4K", ignoreCase = true) ||
            s.contains("2160", ignoreCase = true) ||
            s.contains("uhd", ignoreCase = true) -> "4K"
        s.contains("1440", ignoreCase = true) ||
            s.equals("2K", ignoreCase = true) -> "1440p"
        s.contains("1080", ignoreCase = true) ||
            s.equals("fhd", ignoreCase = true) ||
            s.equals("fullhd", ignoreCase = true) ||
            s.equals("full hd", ignoreCase = true) -> "1080p"
        s.contains("720", ignoreCase = true) ||
            s.equals("hd", ignoreCase = true) -> "720p"
        s.contains("480", ignoreCase = true) ||
            s.equals("sd", ignoreCase = true) -> "480p"
        s.contains("360", ignoreCase = true) -> "360p"
        else -> s
    }
}
