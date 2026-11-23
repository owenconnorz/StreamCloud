package com.streamcloud.app.player

object SourceQualityFilter {
    val options = listOf("4K", "1440p", "1080p", "720p", "480p", "360p", "Unknown")
    val defaultSelected = setOf("4K", "1440p", "1080p")

    fun bucket(source: PlayerSource): String {
        return qualityBucket(source.qualityTag)
            ?: qualityBucket(source.label)
            ?: "Unknown"
    }

    fun filter(
        sources: List<PlayerSource>,
        enabled: Boolean,
        selectedQualities: Set<String>,
    ): List<PlayerSource> =
        if (!enabled) sources else sources.filter { bucket(it) in selectedQualities }

    private fun qualityBucket(value: String?): String? {
        val text = value?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        return when {
            "2160" in text || "4k" in text || "uhd" in text -> "4K"
            "1440" in text || "2k" in text -> "1440p"
            "1080" in text || "full hd" in text || "fullhd" in text || "fhd" in text -> "1080p"
            "720" in text || Regex("""\bhd\b""").containsMatchIn(text) -> "720p"
            "480" in text || Regex("""\bsd\b""").containsMatchIn(text) -> "480p"
            "360" in text -> "360p"
            else -> null
        }
    }
}
