package com.streamcloud.app.data.plugins

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import kotlin.math.ceil

internal enum class CloudStreamContentKind {
    MOVIE,
    TV_SERIES,
    ANIME,
    NSFW,
}

internal fun detectCloudStreamContentKind(
    mediaType: String,
    isAdult: Boolean,
    genreIds: List<Int>,
    genreNames: List<String>,
    originalLanguage: String?,
    originCountries: List<String>,
    title: String,
): CloudStreamContentKind {
    if (isAdult || mediaType.equals("adult", ignoreCase = true) ||
        mediaType.equals("nsfw", ignoreCase = true)
    ) {
        return CloudStreamContentKind.NSFW
    }

    val normalizedGenres = genreNames.map { it.normalizedWords() }
    val isAnime = normalizedGenres.any { it == "anime" } ||
        (
            (16 in genreIds || normalizedGenres.any { it == "animation" }) &&
                (
                    originalLanguage.equals("ja", ignoreCase = true) ||
                        originCountries.any { it.equals("JP", ignoreCase = true) }
                    )
            ) ||
        title.normalizedWords().split(' ').any { it == "anime" }

    return when {
        isAnime -> CloudStreamContentKind.ANIME
        mediaType.equals("tv", ignoreCase = true) ||
            mediaType.equals("series", ignoreCase = true) -> CloudStreamContentKind.TV_SERIES
        else -> CloudStreamContentKind.MOVIE
    }
}

/**
 * Repository type labels are incomplete in some CloudStream catalogs. Only use an explicit,
 * anime-only label to skip a non-anime movie; unlabeled and mixed-type plugins remain eligible.
 */
internal fun InstalledPlugin.supportsPickerContent(kind: CloudStreamContentKind): Boolean {
    val adultPlugin = isAdultPlugin()
    if (kind == CloudStreamContentKind.NSFW) return adultPlugin
    if (adultPlugin) return false

    if (kind == CloudStreamContentKind.MOVIE) {
        val types = tvTypes.orEmpty().map { it.normalizedWords() }.filter { it.isNotBlank() }
        val explicitlyAnimeOnly = types.isNotEmpty() && types.all { it == "anime" }
        if (explicitlyAnimeOnly) return false
    }
    return true
}

internal fun MainAPI.supportsPickerContent(kind: CloudStreamContentKind): Boolean {
    val types = runCatching { supportedTypes }.getOrNull().orEmpty()
    if (types.isEmpty()) return true

    return when (kind) {
        CloudStreamContentKind.MOVIE ->
            types.any { it == TvType.Movie || it == TvType.Torrent || it == TvType.Video }
        CloudStreamContentKind.TV_SERIES ->
            types.any { it == TvType.TvSeries || it == TvType.AsianDrama || it == TvType.Cartoon }
        CloudStreamContentKind.ANIME ->
            types.any { it == TvType.Anime || it == TvType.AnimeMovie || it == TvType.OVA }
        // The plugin-level adult check is authoritative. Keep each of its APIs eligible because
        // adult extensions frequently label their provider types as Movie or TV.
        CloudStreamContentKind.NSFW -> true
    }
}

internal fun searchResponseMatchesContentKind(
    response: SearchResponse,
    kind: CloudStreamContentKind,
): Boolean {
    val typeName = response.javaClass.simpleName.lowercase()
    val isAnimeResponse = "anime" in typeName
    val isMovieResponse = "movie" in typeName
    val isSeriesResponse = "series" in typeName || "tv" in typeName

    return when (kind) {
        CloudStreamContentKind.MOVIE -> !isAnimeResponse && !isSeriesResponse
        CloudStreamContentKind.TV_SERIES -> !isMovieResponse && !isAnimeResponse
        CloudStreamContentKind.ANIME,
        CloudStreamContentKind.NSFW -> true
    }
}

internal fun hasPlausibleCloudStreamTitleMatch(
    resultTitle: String,
    requestedTitle: String,
): Boolean {
    val requested = requestedTitle.normalizedWords()
    val result = resultTitle.normalizedWords()
    if (requested.isBlank() || result.isBlank()) return false
    if (requested == result || result.startsWith("$requested ") || requested.startsWith("$result ")) {
        return true
    }

    val requestedTokens = requested.split(' ').toSet()
    val resultTokens = result.split(' ').toSet()
    val matchedTokens = requestedTokens.intersect(resultTokens).size
    val requiredMatches = ceil(requestedTokens.size * 0.6).toInt().coerceAtLeast(1)
    return matchedTokens >= requiredMatches
}

internal fun cloudStreamNoLinksConfirmed(message: String?): Boolean {
    val normalized = message?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false
    return normalized.startsWith("no search results for") ||
        normalized.startsWith("no results found for this title") ||
        normalized == "no links found" ||
        normalized.startsWith("no streams were returned") ||
        normalized.contains("returned 0 results") ||
        normalized.contains("extractor returned 0 results") ||
        normalized.contains("does not list episode")
}

private fun String.normalizedWords(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in setOf("a", "an", "the") }
        .joinToString(" ")
