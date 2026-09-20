package com.streamcloud.app.data.api

import android.webkit.CookieManager
import com.streamcloud.app.data.network.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.delay
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

data class PornhubHomeSection(
    val id: String,
    val title: String,
    val items: List<AdultItem>,
)

data class PornhubCategory(
    val id: String,
    val title: String,
    val countLabel: String? = null,
    val thumbnail: String? = null,
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

    suspend fun fetchHome(): List<PornhubHomeSection> = withContext(Dispatchers.IO) {
        val html = requestPage("$BASE_URL/")
        val sections = parsePornhubHomeSections(html)
        if (sections.isEmpty() && isChallengePage(html)) {
            throw PornhubUnavailableException(
                "Pornhub requested browser verification. Try again later or use another source.",
            )
        }
        sections
    }

    suspend fun fetchCategories(): List<PornhubCategory> = withContext(Dispatchers.IO) {
        parsePornhubCategories(requestPage("$BASE_URL/categories"))
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
    suspend fun clearSessionCookies(): Boolean {
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
        repeat(3) { attempt ->
            if (attempt > 0) delay(150L)
            manager.flush()
            val remaining = cookieUrls.any { url ->
                runCatching { manager.getCookie(url).orEmpty() }
                    .getOrDefault("")
                    .let(::pornhubCookieNames)
                    .any { it !in setOf("accessAgeDisclaimerPH", "platform") }
            }
            if (!remaining) return true
        }
        return false
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
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Referer", "$BASE_URL/")
                .header("Cookie", pornhubRequestCookieHeader(sessionCookieHeader(currentUrl)))
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
            put("Cookie", pornhubRequestCookieHeader(sessionCookieHeader(streamUrl)))
        }
    }
}

internal fun pornhubCookieNames(cookieHeader: String): Set<String> =
    cookieHeader.split(';')
        .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
        .toSet()

internal fun pornhubRequestCookieHeader(sessionCookieHeader: String): String {
    val current = sessionCookieHeader.trim().trimEnd(';')
    val names = pornhubCookieNames(current)
    return buildList {
        if (current.isNotBlank()) add(current)
        if ("platform" !in names) add("platform=mobile")
    }.joinToString("; ")
}

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
    val candidates = document.select(PORNHUB_CARD_SELECTOR)
    return candidates.mapNotNull(::parsePornhubCard).distinctBy { it.id }
}

internal fun parsePornhubHomeSections(html: String): List<PornhubHomeSection> {
    val document = Jsoup.parse(html, "https://www.pornhub.com")
    val documentOrder = document.getAllElements()
        .withIndex()
        .associate { (index, element) -> element to index }
    val headings = mutableListOf<PornhubHomeHeading>()
    document.select(
        "h2, h3, h4, [class~=sectionTitle], [class~=section-title]",
    ).forEach { heading ->
        // Prefer the semantic heading over a wrapper that merely contains it.
        if (!heading.tagName().matches(Regex("h[2-4]")) &&
            heading.selectFirst("h2, h3, h4") != null
        ) return@forEach
        val title = heading.text().replace(Regex("\\s+"), " ").trim()
        if (title.isBlank() || title.length > 100 || isPornhubAdElement(heading)) {
            return@forEach
        }
        if (heading.parents().any(::isPornhubCardElement)) return@forEach
        val container = heading.parents().firstOrNull { parent ->
            parent.tagName() != "body" &&
                parent.tagName() != "html" &&
                parent.select(PORNHUB_CARD_SELECTOR).isNotEmpty()
        } ?: return@forEach
        headings += PornhubHomeHeading(
            container = container,
            title = title,
            order = documentOrder[heading] ?: return@forEach,
        )
    }

    val containers = headings.map { it.container }.toSet()
    val itemsByHeading = linkedMapOf<PornhubHomeHeading, MutableList<AdultItem>>()
    val unsectioned = mutableListOf<AdultItem>()
    var firstUnsectionedOrder: Int? = null
    document.select(PORNHUB_CARD_SELECTOR)
        .filterNot(::isPornhubAdElement)
        .forEach { candidate ->
            val item = parsePornhubCard(candidate) ?: return@forEach
            val candidateOrder = documentOrder[candidate] ?: return@forEach
            val owner = candidate.parents().firstOrNull { it in containers }
            val heading = headings.asSequence()
                .filter { it.container == owner && it.order < candidateOrder }
                .maxByOrNull { it.order }
            if (heading == null) {
                if (unsectioned.none { it.id == item.id }) {
                    unsectioned += item
                    if (firstUnsectionedOrder == null) firstUnsectionedOrder = candidateOrder
                }
            } else {
                val items = itemsByHeading.getOrPut(heading) { mutableListOf() }
                if (items.none { it.id == item.id }) items += item
            }
        }

    val usedIds = mutableSetOf<String>()
    val orderedSections = itemsByHeading.map { (heading, items) ->
        val baseId = heading.container.id().takeIf(String::isNotBlank)
            ?.let(::pornhubSectionId)
            ?.takeIf(String::isNotBlank)
            ?: pornhubSectionId(heading.title).ifBlank { "videos" }
        var id = baseId
        var suffix = 2
        while (!usedIds.add(id)) id = "$baseId-${suffix++}"
        heading.order to PornhubHomeSection(
            id = id,
            title = heading.title,
            items = items,
        )
    }.toMutableList()
    if (unsectioned.isNotEmpty()) {
        var id = "videos"
        var suffix = 2
        while (!usedIds.add(id)) id = "videos-${suffix++}"
        orderedSections += (firstUnsectionedOrder ?: Int.MAX_VALUE) to PornhubHomeSection(
            id = id,
            title = "Videos",
            items = unsectioned,
        )
    }
    return orderedSections.sortedBy { it.first }.map { it.second }
}

private data class PornhubHomeHeading(
    val container: Element,
    val title: String,
    val order: Int,
)

private const val PORNHUB_CARD_SELECTOR =
    "li[data-video-vkey], li[data-video-segment], div[data-video-vkey]"

private fun isPornhubCardElement(element: Element): Boolean =
    (element.tagName() == "li" &&
        (element.hasAttr("data-video-vkey") || element.hasAttr("data-video-segment"))) ||
        (element.tagName() == "div" && element.hasAttr("data-video-vkey"))

private fun pornhubSectionId(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

private fun isPornhubAdElement(element: Element): Boolean =
    generateSequence(element) { it.parent() }
        .takeWhile { it.tagName() != "body" }
        .any {
            val marker = "${it.id()} ${it.className()}".lowercase()
            Regex("(^|[\\s_-])(ad|ads|advertisement|promoted|sponsored)([\\s_-]|$)")
                .containsMatchIn(marker)
        }

internal fun parsePornhubCategories(html: String): List<PornhubCategory> {
    val document = Jsoup.parse(html, "https://www.pornhub.com")
    return document.select(
        "a[href*='/categories/'], a[href*='/video?c='], a[href*='/video?category=']",
    ).mapNotNull { link ->
        val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
        val title = sequenceOf(
            link.attr("data-title"),
            link.selectFirst("[class*=categoryName], [class*=title]")?.text(),
            image?.attr("alt"),
            link.ownText(),
        ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank)
            ?: return@mapNotNull null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val parsedHref = runCatching { href.toHttpUrl() }.getOrNull()
        val id = parsedHref?.queryParameter("c")
            ?: parsedHref?.queryParameter("category")
            ?: parsedHref?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val surroundingText = link.parent()?.text().orEmpty()
        val countLabel = Regex("""([\d,.]+\s*(?:K|M|B)?\s+videos?)""", RegexOption.IGNORE_CASE)
            .find(surroundingText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val thumbnail = pornhubImageUrl(link)
            ?: link.parent()?.let(::pornhubImageUrl)
        PornhubCategory(
            id = id,
            title = title,
            countLabel = countLabel,
            thumbnail = thumbnail,
        )
    }.filter { it.title.length in 2..60 }
        .distinctBy { it.id.ifBlank { it.title.lowercase() } }
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
    val thumbnail = pornhubImageUrl(element)
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

private val PORNHUB_IMAGE_ATTRIBUTES = listOf(
    "data-poster",
    "data-poster-url",
    "data-thumbnail",
    "data-thumb",
    "data-thumb_url",
    "data-mediumthumb",
    "data-mediumthumb-url",
    "data-preview",
    "data-image",
    "data-original",
    "data-src",
    "src",
)

private fun pornhubImageUrl(element: Element): String? {
    val nodes = sequence {
        yield(element)
        element.select("img, source").forEach { yield(it) }
    }
    return nodes
        .flatMap { node ->
            PORNHUB_IMAGE_ATTRIBUTES.asSequence().map { attribute -> node.attr(attribute) }
        }
        .mapNotNull(::normalizePornhubAssetUrl)
        .firstOrNull()
}

private fun normalizePornhubAssetUrl(raw: String): String? {
    val value = raw.trim()
        .removeSurrounding("\"")
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://")}"
        value.startsWith("https://") -> value
        else -> null
    }
}

internal fun parsePornhubMediaDefinitions(html: String): List<PornhubStreamSource> {
    val jsonArrays = buildList {
        Regex("""["']?mediaDefinitions["']?\s*:\s*""").find(html)?.let { marker ->
            val start = html.indexOf('[', marker.range.last + 1)
            if (start >= 0) extractBalancedJsonArray(html, start)?.let(::add)
        }
        // Some Pornhub responses return the media-definition array directly
        // instead of embedding it in the video page.
        html.trim().takeIf { it.startsWith("[") }?.let(::add)
    }
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    return jsonArrays.flatMap { jsonText ->
        val array = runCatching {
            json.parseToJsonElement(jsonText) as? JsonArray
        }.getOrNull() ?: return@flatMap emptyList()
        array.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val url = item["videoUrl"].firstHttpsString()
                ?.takeUnless {
                    it.contains("get_media_definitions", ignoreCase = true) ||
                        it.contains("/video/get_media", ignoreCase = true)
                }
                ?: return@mapNotNull null
            val qualityPrimitive = item["quality"] as? JsonPrimitive
            val quality = qualityPrimitive?.intOrNull
                ?: qualityPrimitive?.contentOrNull?.filter(Char::isDigit)?.toIntOrNull()
                ?: 0
            val format = (item["format"] as? JsonPrimitive)
                ?.contentOrNull
                .orEmpty()
                .lowercase()
            PornhubStreamSource(url = url, format = format, quality = quality)
        }
    }.distinctBy { it.url }
}

private fun JsonElement?.firstHttpsString(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.let(::normalizePornhubAssetUrl)
    is JsonArray -> firstNotNullOfOrNull { it.firstHttpsString() }
    is JsonObject -> sequenceOf("videoUrl", "url", "src")
        .firstNotNullOfOrNull { key -> this[key].firstHttpsString() }
    else -> null
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
        PornhubRepository.resolve(
            videoId = videoId,
            fallbackPageUrl = fallbackPageUrl,
            // Pornhub's MP4-labelled media definition is often the browser-only
            // /video/get_media endpoint. HLS definitions are direct CDN playlists
            // and are the safest native-player source.
            preferProgressive = false,
        )
}