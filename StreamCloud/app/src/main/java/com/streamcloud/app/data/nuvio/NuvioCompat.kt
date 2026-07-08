package com.streamcloud.app.data.nuvio

import java.net.URI

internal const val NUVIO_DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

internal fun normaliseNuvioIdToken(raw: Any?): String? =
    normaliseNuvioContentId(raw)

internal fun sanitizeNuvioTmdbId(raw: Any?): String? =
    sanitizeNuvioTmdbId(raw, null, null)

internal fun sanitizeNuvioTmdbId(raw: Any?, season: Int?, episode: Int?): String? =
    normaliseNuvioContentId(raw, season, episode)
        ?.takeIf { it != "0" && it.all(Char::isDigit) }

/**
 * Normalises query-parameter names in a raw TMDB-API query string.
 *
 * Fixes two known plugin bugs:
 *  - `append_to _response` (stray space) → `append_to_response`
 *  - `api_kev` (typo) → `api_key`
 */
internal fun sanitizeTmdbApiQueryString(rawQuery: String): String {
    if (rawQuery.isBlank()) return rawQuery
    return rawQuery.split('&').joinToString("&") { pair ->
        val eqIdx = pair.indexOf('=')
        val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
        val value = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
        val decoded = try {
            java.net.URLDecoder.decode(rawKey, "UTF-8")
        } catch (_: Exception) {
            rawKey
        }
        val fixed = when (val stripped = decoded.replace(Regex("""\s+"""), "")) {
            "api_kev" -> "api_key"
            else -> stripped
        }
        java.net.URLEncoder.encode(fixed, "UTF-8") + "=" + value
    }
}

internal fun normaliseNuvioContentId(
    raw: Any?,
    season: Int? = null,
    episode: Int? = null,
): String? {
    val trimmed = raw?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val withoutPrefix = when {
        trimmed.startsWith("tmdb:", ignoreCase = true) -> trimmed.removePrefix("tmdb:")
        trimmed.startsWith("tmdb/", ignoreCase = true) -> trimmed.removePrefix("tmdb/")
        trimmed.startsWith("movie:", ignoreCase = true) -> trimmed.removePrefix("movie:")
        trimmed.startsWith("series:", ignoreCase = true) -> trimmed.removePrefix("series:")
        trimmed.startsWith("tv:", ignoreCase = true) -> trimmed.removePrefix("tv:")
        trimmed.startsWith("show:", ignoreCase = true) -> trimmed.removePrefix("show:")
        trimmed.startsWith("imdb:", ignoreCase = true) -> trimmed.removePrefix("imdb:")
        else -> trimmed
    }
    val withoutEpisodeSuffix = if (season != null && episode != null) {
        withoutPrefix.removeSuffix(":$season:$episode")
    } else {
        withoutPrefix
    }
    return withoutEpisodeSuffix
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore('/')
        .substringBefore(':')
        .trim()
        .ifBlank { trimmed }
}

internal fun tmdbMirrorFallbackUrl(url: String): String? {
    if (url.isBlank()) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path.orEmpty()
    val isTmdbPath = Regex("""^/(3/)?(movie|tv|find|person|search|collection|discover)(/|$)""")
        .containsMatchIn(path)
    val isTmdbHost = host.contains("themoviedb.org")
    if (isTmdbHost || !isTmdbPath) return null

    val normalizedPath = if (path.startsWith("/3/")) path else "/3$path"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return "https://api.themoviedb.org$normalizedPath$query$fragment"
}

internal fun buildNuvioRequestHeaders(
    requestUrl: String,
    method: String,
    incoming: Map<String, String>,
): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    headers.putAll(incoming)

    fun has(name: String): Boolean = headers.keys.any { it.equals(name, ignoreCase = true) }
    fun setIfMissing(name: String, value: String?) {
        if (!value.isNullOrBlank() && !has(name)) headers[name] = value
    }

    val normalizedMethod = method.uppercase()
    val origin = runCatching {
        val uri = URI(requestUrl)
        val scheme = uri.scheme ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    setIfMissing("User-Agent", NUVIO_DEFAULT_USER_AGENT)
    setIfMissing("Accept", "application/json, text/html, */*")
    setIfMissing("Accept-Language", "en-US,en;q=0.9")
    setIfMissing("Accept-Encoding", "gzip, deflate")
    setIfMissing("Cache-Control", "no-cache")
    setIfMissing("Pragma", "no-cache")
    setIfMissing("Referer", origin?.plus("/"))
    if (normalizedMethod !in setOf("GET", "HEAD", "OPTIONS")) {
        setIfMissing("Origin", origin)
    }
    setIfMissing("Sec-Fetch-Dest", "empty")
    setIfMissing("Sec-Fetch-Mode", "cors")
    setIfMissing("Sec-Fetch-Site", "same-origin")
    return headers
}

internal fun inferNuvioDomain(url: String?): String? =
    url?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it).host }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

internal fun NuvioProviderDiagnostics.toSummary(): String {
    val parts = mutableListOf<String>()
    when {
        !errorSummary.isNullOrBlank() -> parts += errorSummary
        exitedEarly -> parts += "Provider exited early"
        else -> parts += "No streams found"
    }
    parts += "phase=$phase"
    lastStatus?.takeIf { it > 0 }?.let { parts += "status=$it" }
    lastDomain?.takeIf { it.isNotBlank() }?.let { parts += "host=$it" }
    return parts.joinToString(" · ")
}
