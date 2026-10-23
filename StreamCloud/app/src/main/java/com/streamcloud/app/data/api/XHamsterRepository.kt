package com.streamcloud.app.data.api

import android.webkit.CookieManager
import com.streamcloud.app.data.network.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class XHamsterPage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

data class XHamsterCategory(
    val path: String,
    val title: String,
    val thumbnail: String?,
) {
    val id: String get() = path
}

class XHamsterUnavailableException(message: String) : Exception(message)

private const val XHAMSTER_DEFAULT_PAGE_SIZE = 50
private val XHAMSTER_PAGE_JSON = Json { isLenient = true }

/**
 * Reads XHamster's public listing pages. Playback stays on the official video
 * page so the provider's own player and access checks remain in control.
 */
object XHamsterRepository {
    private const val BASE_URL = "https://xhamster.com"
    private val cookieHosts = listOf(
        "https://xhamster.com",
        "https://www.xhamster.com",
        "https://m.xhamster.com",
    )
    private val cookiePaths = listOf("/", "/login")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun fetch(
        query: String,
        page: Int,
        categoryPath: String? = null,
    ): XHamsterPage =
        withContext(Dispatchers.IO) {
            val safePage = page.coerceAtLeast(1)
            val html = requestPage(buildXHamsterListingUrl(query, safePage, categoryPath))
            parseXHamsterListing(html, safePage)
        }

    suspend fun fetchCategories(): List<XHamsterCategory> = withContext(Dispatchers.IO) {
        // XHamster's main category directory is split into letter and format pages.
        // Follow the official directory links rather than a locale-specific catalog.
        val directoryHtml = requestPage("$BASE_URL/categories")
        val indexPaths = parseXHamsterCategoryIndexPaths(directoryHtml)
            .filterNot { it == "/categories" }
        if (indexPaths.isEmpty()) {
            throw XHamsterUnavailableException(
                "XHamster's category directory did not provide its category pages.",
            )
        }

        val requestLimit = Semaphore(4)
        val otherPages = coroutineScope {
            indexPaths.map { path ->
                async {
                    requestLimit.withPermit {
                        requestPage("$BASE_URL$path")
                    }
                }
            }.awaitAll()
        }

        (listOf(directoryHtml) + otherPages)
            .flatMap(::parseXHamsterCategories)
            .distinctBy(XHamsterCategory::path)
            .sortedBy { it.title.lowercase() }
    }

    fun videoPageUrl(videoId: String): String {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
            "XHamster did not provide a valid video identifier."
        }
        return "$BASE_URL/videos/$videoId"
    }

    /**
     * Remove only XHamster cookies from WebView's shared cookie store.
     * Other provider sessions must remain untouched.
     */
    suspend fun clearSessionCookies(): Boolean {
        val manager = CookieManager.getInstance()
        val cookieNames = cookieHosts.flatMap { host ->
            cookiePaths.flatMap { path ->
                runCatching { manager.getCookie("$host$path").orEmpty() }
                    .getOrDefault("")
                    .split(';')
                    .mapNotNull { cookie ->
                        cookie.trim()
                            .substringBefore('=')
                            .takeIf(String::isNotBlank)
                    }
            }
        }.distinct()

        cookieNames.forEach { name ->
            cookieHosts.forEach { host ->
                cookiePaths.forEach { path ->
                    manager.setCookie(
                        "$host$path",
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure",
                    )
                    manager.setCookie(
                        "$host$path",
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.xhamster.com; Path=$path; Secure",
                    )
                }
            }
        }
        manager.flush()
        repeat(3) { attempt ->
            if (attempt > 0) delay(150L)
            manager.flush()
            val remaining = cookieHosts.any { host ->
                cookiePaths.any { path ->
                    runCatching { manager.getCookie("$host$path").orEmpty() }
                        .getOrDefault("")
                        .split(';')
                        .map { it.trim().substringBefore('=') }
                        .any { it in cookieNames }
                }
            }
            if (!remaining) return true
        }
        return cookieNames.isEmpty()
    }

    private fun requestPage(url: String): String {
        var currentUrl = url
        repeat(5) { redirectCount ->
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", BrowserHeaders.USER_AGENT)
                .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Referer", "$BASE_URL/")
            val providerCookies = runCatching {
                CookieManager.getInstance().getCookie(currentUrl)
            }.getOrNull()
            if (!providerCookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", providerCookies)
            }
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    if (redirectCount == 4) {
                        throw XHamsterUnavailableException(
                            "XHamster redirected too many times.",
                        )
                    }
                    val location = response.header("Location").orEmpty()
                    val nextUrl = response.request.url.resolve(location)
                    if (nextUrl == null || !isAllowedXHamsterUrl(nextUrl.toString())) {
                        throw XHamsterUnavailableException(
                            "XHamster redirected outside its official site.",
                        )
                    }
                    currentUrl = nextUrl.toString()
                    return@use
                }

                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        403 -> "XHamster blocked this request."
                        404 -> "This XHamster page is no longer available."
                        429 -> "XHamster rate-limited this device. Please wait and try again."
                        else -> "XHamster returned HTTP ${response.code}."
                    }
                    throw XHamsterUnavailableException(message)
                }

                return response.body?.string()
                    ?.takeIf(String::isNotBlank)
                    ?: throw XHamsterUnavailableException("XHamster returned an empty page.")
            }
        }
        throw XHamsterUnavailableException("XHamster redirected too many times.")
    }
}

internal fun buildXHamsterListingUrl(
    query: String,
    page: Int,
    categoryPath: String? = null,
): String {
    val safePage = page.coerceAtLeast(1)
    val safeCategoryPath = categoryPath?.let {
        requireNotNull(normalizeXHamsterCategoryPath(it)) {
            "XHamster category link was invalid."
        }
    }
    val builder = when {
        safeCategoryPath != null -> "https://xhamster.com$safeCategoryPath".toHttpUrl().newBuilder()
        else -> "https://xhamster.com/".toHttpUrl().newBuilder()
    }
    if (safeCategoryPath != null) {
        if (safePage > 1) builder.addPathSegment(safePage.toString())
    } else if (query.isBlank()) {
        if (safePage > 1) builder.addPathSegment(safePage.toString())
    } else {
        builder
            .addPathSegment("search")
            .addPathSegment(query.trim())
            .addQueryParameter("page", safePage.toString())
    }
    return builder.build().toString()
}

internal fun parseXHamsterCategories(html: String): List<XHamsterCategory> {
    val document = Jsoup.parse(html)
    if (document.body()?.hasClass("xh-scroll-disabled") == true) {
        throw XHamsterUnavailableException(
            "Complete XHamster's age confirmation on its official site, then try again.",
        )
    }
    if (isXHamsterVerificationPage(html)) {
        throw XHamsterUnavailableException(
            "XHamster requested browser verification. Try again later.",
        )
    }

    return document.select("a[href]")
        .mapNotNull { anchor ->
            val path = normalizeXHamsterCategoryPath(anchor.attr("href"))
                ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val title = image?.attr("alt")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: anchor.selectFirst("h3")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: anchor.text().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            XHamsterCategory(
                path = path,
                title = title,
                thumbnail = image?.let {
                    normalizeXHamsterAssetUrl(
                        it.attr("src").ifBlank { it.attr("data-src") },
                    )
                },
            )
        }
        .distinctBy(XHamsterCategory::path)
        .sortedBy { it.title.lowercase() }
}

internal fun parseXHamsterCategoryIndexPaths(html: String): List<String> {
    val document = Jsoup.parse(html)
    return document.select("a[href]")
        .mapNotNull { anchor ->
            normalizeXHamsterCategoryIndexPath(anchor.attr("href"))
        }
        .distinct()
        .sorted()
}

private fun normalizeXHamsterCategoryIndexPath(raw: String): String? {
    val value = raw.trim().takeIf(String::isNotBlank) ?: return null
    val parsed = runCatching {
        "https://xhamster.com/".toHttpUrl().resolve(value)
    }.getOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host != "xhamster.com") return null
    val path = parsed.encodedPath.removeSuffix("/")
    return path.takeIf {
        Regex("^/categories(?:-(?:[0-9a-z]|4k|vr))?$").matches(it)
    }
}

private fun normalizeXHamsterCategoryPath(raw: String): String? {
    val value = raw.trim().takeIf(String::isNotBlank) ?: return null
    val parsed = runCatching {
        "https://xhamster.com/".toHttpUrl().resolve(value)
    }.getOrNull() ?: return null
    if (!isAllowedXHamsterUrl(parsed.toString())) return null
    val path = parsed.encodedPath.removeSuffix("/")
    if (!Regex("^/categories/[a-z0-9-]+$").matches(path)) {
        return null
    }
    return path
}

internal fun parseXHamsterListing(
    html: String,
    requestedPage: Int,
): XHamsterPage {
    val document = Jsoup.parse(html)
    if (document.body()?.hasClass("xh-scroll-disabled") == true) {
        throw XHamsterUnavailableException(
            "Complete XHamster's age confirmation on its official site, then try again.",
        )
    }

    val script = document.selectFirst("#initials-script")
        ?: throw XHamsterUnavailableException(
            if (isXHamsterVerificationPage(html)) {
                "XHamster requested browser verification. Try again later."
            } else {
                "XHamster's page could not be read. Try again later."
            },
        )
    val rawJson = script.data().ifBlank { script.html() }
    val jsonStart = rawJson.indexOf('{')
    val jsonEnd = rawJson.lastIndexOf('}')
    if (jsonStart < 0 || jsonEnd < jsonStart) {
        throw XHamsterUnavailableException("XHamster returned unreadable listing data.")
    }

    val root = runCatching {
        XHAMSTER_PAGE_JSON.parseToJsonElement(rawJson.substring(jsonStart, jsonEnd + 1)).jsonObject
    }.getOrElse {
        throw XHamsterUnavailableException("XHamster returned unreadable listing data.")
    }

    val videoArray = root.arrayAt("searchResult", "videoThumbProps")
        ?: root.arrayAt("layoutPage", "videoListProps", "videoThumbProps")
        ?: root.arrayAt("layoutPage", "videoThumbProps")
        ?: root.arrayAt(
            "pagesCategoryComponent",
            "trendingVideoListProps",
            "videoThumbProps",
        )
        ?: JsonArray(emptyList())

    val items = videoArray.mapNotNull { element ->
        val video = element as? JsonObject ?: return@mapNotNull null
        val pageUrl = normalizeXHamsterVideoUrl(video.string("pageURL"))
            ?: return@mapNotNull null
        val id = pageUrl.substringAfterLast('/').takeIf {
            it.matches(Regex("[A-Za-z0-9_-]{1,100}"))
        } ?: return@mapNotNull null
        val title = video.string("title")?.trim()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val thumbnail = normalizeXHamsterAssetUrl(
            video.string("imageURL") ?: video.string("thumbURL"),
        )
        val previewImage = normalizeXHamsterAssetUrl(
            video.string("previewThumbURL") ?: video.string("thumbURL"),
        ) ?: thumbnail
        val duration = video.int("duration")?.takeIf { it >= 0 }?.let(::formatXHamsterDuration)

        AdultItem(
            id = id,
            title = title,
            thumbnail = thumbnail,
            previewImage = previewImage,
            durationLabel = duration,
            streamUrl = null,
            source = AdultSource.XHamster,
            embedUrl = pageUrl,
            views = video.string("views")?.takeIf(String::isNotBlank),
            tags = video.obj("landing")?.string("name"),
        )
    }.distinctBy(AdultItem::id)

    val pagination = root.obj("pagination")
    val paginationComponent = root.obj("paginationComponent")
        ?: root.obj("pagesCategoryComponent")?.obj("paginationProps")
    val pageData = root.obj("layoutPage")
    val currentPage = paginationComponent?.int("currentPageNumber")
        ?: root.int("page")
        ?: pageData?.int("pageNum")
        ?: requestedPage
    val lastPage = paginationComponent?.int("lastPageNumber")
        ?: pagination?.int("maxPages")
        ?: pagination?.int("maxPage")
    val pageSize = root.int("perPage")
        ?: pageData?.obj("videoListProps")?.int("perPage")
        ?: root.obj("pagesCategoryComponent")
            ?.obj("trendingVideoListProps")
            ?.int("perPage")
        ?: XHAMSTER_DEFAULT_PAGE_SIZE

    val hasMore = items.isNotEmpty() && if (lastPage != null) {
        currentPage < lastPage
    } else {
        items.size >= pageSize
    }
    return XHamsterPage(items, hasMore)
}

private fun isXHamsterVerificationPage(html: String): Boolean {
    val lower = html.lowercase()
    return listOf(
        "cf-chl-",
        "checking your browser",
        "verify you are human",
        "access denied",
    ).any(lower::contains)
}

private fun isAllowedXHamsterUrl(url: String): Boolean {
    val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
    return parsed.scheme == "https" && parsed.port == 443 &&
        (parsed.host == "xhamster.com" || parsed.host.endsWith(".xhamster.com"))
}

private fun normalizeXHamsterVideoUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    val parsed = runCatching {
        "https://xhamster.com/".toHttpUrl().resolve(value)
    }.getOrNull() ?: return null
    if (!isAllowedXHamsterUrl(parsed.toString()) ||
        !parsed.encodedPath.startsWith("/videos/")
    ) return null
    return parsed.newBuilder().query(null).fragment(null).build().toString()
}

private fun normalizeXHamsterAssetUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    val normalized = if (value.startsWith("//")) "https:$value" else value
    val parsed = runCatching { normalized.toHttpUrl() }.getOrNull() ?: return null
    val allowedHost = parsed.host == "xhamster.com" ||
        parsed.host.endsWith(".xhamster.com") ||
        parsed.host == "xhcdn.com" ||
        parsed.host.endsWith(".xhcdn.com")
    return parsed.toString().takeIf {
        parsed.scheme == "https" && parsed.port == 443 && allowedHost
    }
}

private fun formatXHamsterDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun JsonObject.arrayAt(vararg path: String): JsonArray? {
    var current: JsonElement = this
    for (key in path) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current as? JsonArray
}

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
