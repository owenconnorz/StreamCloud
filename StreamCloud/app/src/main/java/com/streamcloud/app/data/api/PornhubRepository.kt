package com.streamcloud.app.data.api

import android.webkit.CookieManager
import com.streamcloud.app.data.network.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

data class PornhubPage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

data class PornhubStreamSource(
    val url: String,
    val format: String,
    val quality: Int,
)

data class PornhubResolvedPlayback(
    val url: String,
    val headers: Map<String, String>,
)

class PornhubUnavailableException(message: String) : Exception(message)

object PornhubRepository {
    private const val BASE_URL = "https://www.pornhub.com"
    private const val DEFAULT_COOKIE = "accessAgeDisclaimerPH=1; platform=mobile"
    private val cookieHosts = listOf(
        "https://www.pornhub.com",
        "https://pornhub.com",
        "https://m.pornhub.com",
    )
    private val cookiePaths = listOf("/", "/login", "/video", "/view_video.php")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun fetch(query: String, page: Int): PornhubPage = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) {
            "$BASE_URL/video".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .build()
        } else {
            "$BASE_URL/video/search".toHttpUrl().newBuilder()
                .addQueryParameter("search", query.trim())
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .build()
        }
        val html = requestPage(url.toString())
        val parsed = parsePornhubListing(html)
        if (parsed.isEmpty() && isChallengePage(html)) {
            throw PornhubUnavailableException(
                "Pornhub requested browser verification. Try again later or use another source.",
            )
        }
        PornhubPage(
            items = parsed,
            hasMore = parsed.isNotEmpty() && hasNextPornhubPage(html, page),
        )
    }

    suspend fun resolve(
        videoId: String,
        fallbackPageUrl: String,
        preferProgressive: Boolean = false,
    ): PornhubResolvedPlayback = withContext(Dispatchers.IO) {
        val pageUrl = normalizeVideoUrl(videoId, fallbackPageUrl)
        val html = requestPage(pageUrl)
        if (isChallengePage(html)) {
            throw PornhubUnavailableException(
                "Pornhub requested browser verification. Try again later or use another source.",
            )
        }
        val sources = parsePornhubMediaDefinitions(html)
        val chosen = choosePornhubSource(sources, preferProgressive)
            ?: throw PornhubUnavailableException("Pornhub did not provide a playable stream.")
        PornhubResolvedPlayback(chosen.url, playbackHeaders(pageUrl, chosen.url))
    }

    /**
     * Read the WebView cookie jar at request time. Cookies are not copied into
     * DataStore, logged, or sent to any host other than Pornhub.
     */
    fun sessionCookieHeader(url: String = BASE_URL): String {
        if (!isAllowedPornhubUrl(url)) return ""
        return runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            .orEmpty()
    }

    fun hasSessionCookies(url: String = BASE_URL): Boolean =
        pornhubCookieNames(sessionCookieHeader(url)).any {
            it != "accessAgeDisclaimerPH" && it != "platform"
        }

    /**
     * Clear only Pornhub domains. The shared WebView cookie store also contains
     * Reddit and other account sessions, so removeAllCookies() is not acceptable.
     */
    fun clearSessionCookies(): Boolean {
        val manager = CookieManager.getInstance()
        val cookieUrls = cookieHosts.flatMap { host ->
            cookiePaths.map { path -> "$host$path" }
        }
        val names = cookieUrls
            .flatMap { url ->
                runCatching { manager.getCookie(url).orEmpty() }
                    .getOrDefault("")
                    .let(::pornhubCookieNames)
            }
            .distinct()
        names.forEach { name ->
            cookieHosts.forEach { host ->
                cookiePaths.forEach { path ->
                    manager.setCookie(
                        "$host$path",
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure",
                    )
                    manager.setCookie(
                        "$host$path",
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.pornhub.com; Path=$path; Secure",
                    )
                }
            }
        }
        manager.flush()
        return cookieUrls.none { url ->
            runCatching { manager.getCookie(url).orEmpty() }
                .getOrDefault("")
                .let(::pornhubCookieNames)
                .any { it !in setOf("accessAgeDisclaimerPH", "platform") }
        }
    }

    private fun requestPage(url: String): String {
        var currentUrl = url
        repeat(6) { redirectCount ->
            if (!isAllowedPornhubUrl(currentUrl)) {
                throw PornhubUnavailableException("Pornhub redirected outside its official site.")
            }
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", BrowserHeaders.USER_AGENT)
                .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Referer", "$BASE_URL/")
                .header("Cookie", sessionCookieHeader(currentUrl).ifBlank { DEFAULT_COOKIE })
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    if (redirectCount == 5) {
                        throw PornhubUnavailableException("Pornhub redirected too many times.")
                    }
                    val location = response.header("Location").orEmpty()
                    currentUrl = resolveAllowedPornhubRedirect(currentUrl, location)
                        ?: throw PornhubUnavailableException(
                            "Pornhub redirected outside its official site.",
                        )
                    return@use
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw PornhubUnavailableException(
                        when (response.code) {
                            403 -> "Pornhub blocked this request."
                            404 -> "This Pornhub page is no longer available."
                            429 -> "Pornhub rate-limited this device. Please wait and try again."
                            else -> "Pornhub returned HTTP ${response.code}."
                        },
                    )
                }
                if (body.isBlank()) throw PornhubUnavailableException("Pornhub returned an empty page.")
                return body
            }
        }
        throw PornhubUnavailableException("Pornhub redirected too many times.")
    }

    private fun normalizeVideoUrl(videoId: String, fallbackPageUrl: String): String {
        val fallback = when {
            fallbackPageUrl.startsWith("//") -> "https:$fallbackPageUrl"
            fallbackPageUrl.startsWith("/") -> "$BASE_URL$fallbackPageUrl"
            fallbackPageUrl.startsWith("https://") -> fallbackPageUrl
            else -> ""
        }
        if (isAllowedPornhubUrl(fallback) &&
            runCatching { fallback.toHttpUrl().encodedPath == "/view_video.php" }.getOrDefault(false)
        ) return fallback
        val cleanId = videoId.removePrefix("pornhub://").trim()
        require(cleanId.matches(Regex("[A-Za-z0-9_-]+"))) {
            "Pornhub did not provide a valid video identifier."
        }
        return "$BASE_URL/view_video.php".toHttpUrl().newBuilder()
            .addQueryParameter("viewkey", cleanId)
            .build()
            .toString()
    }

    private fun playbackHeaders(pageUrl: String, streamUrl: String) = buildMap {
        put("User-Agent", BrowserHeaders.USER_AGENT)
        put("Referer", pageUrl)
        put("Origin", BASE_URL)
        put("Accept", "*/*")
        val host = runCatching { streamUrl.toHttpUrl().host }.getOrNull().orEmpty()
        if (host == "pornhub.com" || host.endsWith(".pornhub.com")) {
            put("Cookie", sessionCookieHeader(streamUrl).ifBlank { DEFAULT_COOKIE })
        }
    }
}

internal fun pornhubCookieNames(cookieHeader: String): Set<String> =
    cookieHeader.split(';')
        .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
        .toSet()

internal fun isAllowedPornhubUrl(url: String): Boolean {
    val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
    return parsed.scheme == "https" &&
        (parsed.host == "pornhub.com" || parsed.host.endsWith(".pornhub.com"))
}

internal fun resolveAllowedPornhubRedirect(currentUrl: String, location: String): String? {
    val resolved = runCatching { currentUrl.toHttpUrl().resolve(location)?.toString() }
        .getOrNull()
        ?: return null
    return resolved.takeIf(::isAllowedPornhubUrl)
}

internal fun parsePornhubListing(html: String): List<AdultItem> {
    val document = Jsoup.parse(html, "https://www.pornhub.com")
    val candidates = document.select(
        "li[data-video-vkey], li[data-video-segment], div[data-video-vkey]",
    )
    return candidates.mapNotNull(::parsePornhubCard).distinctBy { it.id }
}

private fun parsePornhubCard(element: Element): AdultItem? {
    val link = element.selectFirst("a[href*='view_video.php?viewkey=']") ?: return null
    val pageUrl = link.absUrl("href").ifBlank {
        val href = link.attr("href")
        when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://www.pornhub.com$href"
            else -> href
        }
    }
    val id = element.attr("data-video-vkey").ifBlank {
        pageUrl.toHttpUrlOrNull()?.queryParameter("viewkey").orEmpty()
    }.ifBlank { return null }
    val title = sequenceOf(
        element.selectFirst("div.title a")?.attr("title"),
        element.selectFirst("div.title a")?.text(),
        link.attr("title"),
        element.selectFirst("img")?.attr("alt"),
    ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank) ?: return null
    val image = element.selectFirst("img")
    val thumbnail = sequenceOf("data-src", "data-thumb_url", "data-mediumthumb", "src")
        .map { image?.attr(it).orEmpty() }
        .firstOrNull { it.startsWith("http") || it.startsWith("//") }
        ?.let { if (it.startsWith("//")) "https:$it" else it }
    val duration = element.selectFirst("span[class*=time], var.duration")?.text()?.trim()
        ?.takeIf(String::isNotBlank)
    val views = element.selectFirst("div.videoViews, div.views, span.views")?.text()
        ?.replace(Regex("(?i)views?"), "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val rating = element.selectFirst("div.value, span.value, span.rating")?.text()?.trim()
        ?.takeIf(String::isNotBlank)
    val tags = element.select("a[class*=uploaderLink], span[class*=uploaderLink]")
        .map(Element::text)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString()
        .takeIf(String::isNotBlank)
    return AdultItem(
        id = id,
        title = title,
        thumbnail = thumbnail,
        previewImage = thumbnail,
        durationLabel = duration,
        streamUrl = null,
        source = AdultSource.Pornhub,
        embedUrl = pageUrl,
        views = views,
        rating = rating,
        tags = tags,
    )
}

internal fun parsePornhubMediaDefinitions(html: String): List<PornhubStreamSource> {
    val marker = Regex("""["']?mediaDefinitions["']?\s*:\s*""").find(html) ?: return emptyList()
    val start = html.indexOf('[', marker.range.last + 1)
    if (start < 0) return emptyList()
    val jsonText = extractBalancedJsonArray(html, start) ?: return emptyList()
    val array = runCatching {
        Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(jsonText) as? JsonArray
    }.getOrNull() ?: return emptyList()
    return array.mapNotNull { raw ->
        val item = raw.jsonObject
        val url = item["videoUrl"]?.jsonPrimitive?.contentOrNull
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.takeIf { it.startsWith("https://") }
            ?: return@mapNotNull null
        val qualityPrimitive = item["quality"]?.jsonPrimitive
        val quality = qualityPrimitive?.intOrNull
            ?: qualityPrimitive?.contentOrNull?.filter(Char::isDigit)?.toIntOrNull()
            ?: 0
        val format = item["format"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        PornhubStreamSource(url = url, format = format, quality = quality)
    }.distinctBy { it.url }
}

internal fun choosePornhubSource(
    sources: List<PornhubStreamSource>,
    preferProgressive: Boolean = false,
): PornhubStreamSource? {
    val playable = sources.filterNot { it.url.endsWith(".json", ignoreCase = true) }
    val progressive = playable.filter {
        it.format.contains("mp4") || it.url.substringBefore('?').endsWith(".mp4", ignoreCase = true)
    }
    val hls = playable.filter {
        it.format.contains("hls") || it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    }
    return if (preferProgressive) {
        progressive.maxByOrNull { it.quality } ?: hls.maxByOrNull { it.quality }
    } else {
        hls.maxByOrNull { it.quality } ?: progressive.maxByOrNull { it.quality }
    }
}

internal fun hasNextPornhubPage(html: String, currentPage: Int): Boolean {
    val document = Jsoup.parse(html)
    val next = document.selectFirst(
        "li.page_next a, a.page_next, a[rel=next], a[aria-label*=Next]",
    )
    if (next != null) return true
    return document.select("li[data-video-vkey], li[data-video-segment]").isNotEmpty() &&
        !document.text().contains("No videos found", ignoreCase = true) &&
        currentPage == 1
}

private fun extractBalancedJsonArray(text: String, start: Int): String? {
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in start until text.length) {
        val c = text[index]
        if (quoted) {
            if (escaped) escaped = false
            else when (c) {
                '\\' -> escaped = true
                '"' -> quoted = false
            }
            continue
        }
        when (c) {
            '"' -> quoted = true
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}

private fun isChallengePage(html: String): Boolean {
    val text = Jsoup.parse(html).text().trim()
    return text.equals("Loading...", ignoreCase = true) ||
        html.contains("id=\"captcha\"", ignoreCase = true) ||
        html.contains("class=\"captcha", ignoreCase = true) ||
        html.contains("cf-chl-", ignoreCase = true)
}

private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

object PornhubPlaybackResolver {
    suspend fun resolve(videoId: String, fallbackPageUrl: String): PornhubResolvedPlayback =
        PornhubRepository.resolve(videoId, fallbackPageUrl)
}