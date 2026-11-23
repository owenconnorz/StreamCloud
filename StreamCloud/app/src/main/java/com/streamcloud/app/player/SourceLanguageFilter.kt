package com.streamcloud.app.player

object SourceLanguageFilter {
    val options = listOf(
        "English", "Spanish", "French", "German", "Italian", "Portuguese",
        "Japanese", "Korean", "Chinese", "Russian", "Arabic", "Hindi",
        "Turkish", "Polish", "Dutch", "Thai", "Multi", "Unknown",
    )
    val defaultSelected = setOf("English", "Multi", "Unknown")

    private val aliases = linkedMapOf(
        "English" to setOf("english", "eng", "en"),
        "Spanish" to setOf("spanish", "espanol", "spa", "es"),
        "French" to setOf("french", "francais", "fra", "fre", "fr"),
        "German" to setOf("german", "deutsch", "ger", "deu", "de"),
        "Italian" to setOf("italian", "ita", "it"),
        "Portuguese" to setOf("portuguese", "por", "pt"),
        "Japanese" to setOf("japanese", "jpn", "ja", "jp"),
        "Korean" to setOf("korean", "kor", "ko", "kr"),
        "Chinese" to setOf("chinese", "zho", "chi", "cmn", "zh"),
        "Russian" to setOf("russian", "rus", "ru"),
        "Arabic" to setOf("arabic", "ara", "ar"),
        "Hindi" to setOf("hindi", "hin", "hi"),
        "Turkish" to setOf("turkish", "tur", "tr"),
        "Polish" to setOf("polish", "pol", "pl"),
        "Dutch" to setOf("dutch", "nld", "dut", "nl"),
        "Thai" to setOf("thai", "tha", "th"),
    )

    fun bucket(source: PlayerSource): String =
        bucket(source.languageTag, source.label) ?: "Unknown"

    fun matches(source: PlayerSource, selectedLanguages: Set<String>): Boolean =
        bucket(source) in selectedLanguages

    fun matchesTrack(
        selectedLanguages: Set<String>,
        vararg languageOrLabel: String?,
    ): Boolean =
        bucket(*languageOrLabel) in selectedLanguages

    fun filter(
        sources: List<PlayerSource>,
        enabled: Boolean,
        selectedLanguages: Set<String>,
    ): List<PlayerSource> =
        if (!enabled) sources else sources.filter { matches(it, selectedLanguages) }

    private fun bucket(vararg values: String?): String? {
        for (value in values) {
            val words = value
                ?.lowercase()
                ?.replace(Regex("[^a-z0-9]+"), " ")
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.toSet()
                .orEmpty()
            if (words.isEmpty()) continue
            if (
                "multi" in words ||
                "dual" in words ||
                "multilingual" in words ||
                "multi-audio" in value.orEmpty().lowercase() ||
                "dual audio" in value.orEmpty().lowercase()
            ) return "Multi"
            val matches = aliases.entries.filter { (_, names) -> names.any(words::contains) }
            if (matches.size > 1) return "Multi"
            if (matches.size == 1) return matches.single().key
        }
        return null
    }
}
