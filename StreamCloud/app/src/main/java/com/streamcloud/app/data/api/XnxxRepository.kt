package com.streamcloud.app.data.api

import android.webkit.CookieManager
import com.streamcloud.app.data.network.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

data class XnxxPage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

data class XnxxResolvedPlayback(
    val url: String,
    val headers: Map<String, String>,
)

internal data class XnxxStreamSource(
    val quality: String,
    val url: String,
)

/**
 * Reads XNXX's public listings and resolves direct, provider-published media
 * URLs for StreamCloud's native player. It does not load the provider player
 * in a WebView or bypass age, region, or browser-verification checks.
 */
object XnxxRepository {
    private const val BASE_URL = "https://www.xnxx.com/"
    private const val BEST_URL = "https://www.xnxx.com/best"
    private const val PAGE_SIZE = 18
    private val cookieHosts = listOf("https://www.xnxx.com", "https://xnxx.com")
    private val cookiePaths = listOf("/", "/account", "/account/")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Volatile
    private var bestArchivePrefix: String? = null

    suspend fun fetchPage(query: String, page: Int): XnxxPage = withContext(Dispatchers.IO) {
        require(page > 0) { "Page must be positive." }

        val cleanQuery = query.trim()
        val html = if (cleanQuery.isNotBlank()) {
            requestHtml(buildSearchUrl(cleanQuery, page))
        } else if (page == 1) {
            requestHtml(BEST_URL)
        } else {
            val archivePrefix = bestArchivePrefix ?: run {
                val firstPage = requestHtml(BEST_URL)
                parseBestArchivePrefix(firstPage)?.also { bestArchivePrefix = it }
            } ?: throw IOException("XNXX did not provide a usable next page.")
            requestHtml("$BASE_URL$archivePrefix/$page")
        }

        if (cleanQuery.isBlank() && page == 1) {
            bestArchivePrefix = parseBestArchivePrefix(html) ?: bestArchivePrefix
        }
        parseListing(html, cleanQuery.isBlank(), page)
    }

    suspend fun resolvePlayback(pageUrl: String): XnxxResolvedPlayback =
        withContext(Dispatchers.IO) {
            val safePageUrl = normalizeXnxxVideoPageUrl(pageUrl)
                ?: throw IOException("XNXX did not provide a valid video page.")
            val html = requestHtml(safePageUrl)
            val stream = parseXnxxStreamSources(html).firstOrNull()
                ?: throw IOException(
                    "XNXX did not expose a native video stream. Complete any official " +
                        "age or browser check on XNXX and retry.",
                )
            XnxxResolvedPlayback(
                url = stream.url,
                headers = mapOf(
                    "User-Agent" to BrowserHeaders.USER_AGENT,
                    "Referer" to safePageUrl,
                    "Origin" to "https://www.xnxx.com",
                    "Accept" to "*/*",
                ),
            )
        }

    /**
     * Clears only XNXX site cookies. The WebView cookie store is otherwise kept
     * on-device so the official sign-in and verification session can persist.
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
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.xnxx.com; Path=$path; Secure",
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

    internal fun buildSearchUrl(query: String, page: Int): String {
        require(page > 0) { "Page must be positive." }
        require(query.isNotBlank()) { "Search text cannot be blank." }
        return BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment(query.trim())
            .addPathSegment((page - 1).toString())
            .build()
            .toString()
    }

    internal fun parseListing(
        html: String,
        isBestListing: Boolean,
        page: Int,
    ): XnxxPage {
        require(page > 0) { "Page must be positive." }
        val document = Jsoup.parse(html, BASE_URL)
        val pageText = document.text().lowercase()
        if (isVerificationPage(pageText)) {
            throw IOException(
                "XNXX requested official age or browser verification. Complete it on XNXX, " +
                    "then retry; StreamCloud does not bypass those checks.",
            )
        }

        val items = document.select("div.thumb-block:not(.thumb-cat)")
            .mapNotNull(::toAdultItem)
            .distinctBy(AdultItem::id)
        val currentSitePage = if (isBestListing) page else page - 1
        val lastSitePage = document.select("a[href]")
            .mapNotNull { listingPageNumber(it.attr("href"), isBestListing) }
            .maxOrNull()
        val hasMore = lastSitePage?.let { it > currentSitePage }
            ?: (items.size >= PAGE_SIZE)
        return XnxxPage(items, hasMore)
    }

    internal fun parseXnxxStreamSources(html: String): List<XnxxStreamSource> =
        listOf(
            "High" to "setVideoUrlHigh",
            "HLS" to "setVideoHLS",
            "Low" to "setVideoUrlLow",
        ).mapNotNull { (quality, setter) ->
            val rawUrl = Regex(
                """html5player\.$setter\s*\(\s*['"]([^'"]+)['"]\s*\)""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val decodedUrl = rawUrl
                .replace("\\/", "/")
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
            val safeUrl = normalizeXnxxMediaUrl(decodedUrl) ?: return@mapNotNull null
            XnxxStreamSource(quality, safeUrl)
        }

    private fun toAdultItem(block: org.jsoup.nodes.Element): AdultItem? {
        val validAnchors = block.select("a[href]")
            .mapNotNull { anchor ->
                normalizeXnxxVideoPageUrl(anchor.attr("href"))?.let { anchor to it }
            }
        val (anchor, pageUrl) = validAnchors.firstOrNull {
            it.first.attr("title").isNotBlank()
        } ?: validAnchors.firstOrNull() ?: return null

        val id = VIDEO_ID_PATTERN.find(pageUrl.toHttpUrl().encodedPath)
            ?.groupValues?.getOrNull(1)
            ?: return null
        val title = anchor.attr("title").ifBlank {
            block.selectFirst(".thumb-under, .thumb-info .title, .thumb-info")?.text().orEmpty()
        }.trim()
        if (title.isBlank()) return null

        val image = block.selectFirst("img")
        val thumbnail = sequenceOf(
            image?.attr("data-src"),
            image?.attr("data-src-avif"),
            image?.attr("src"),
        ).mapNotNull(::normalizeXnxxImageUrl).firstOrNull()

        return AdultItem(
            id = id,
            title = title,
            thumbnail = thumbnail,
            previewImage = thumbnail,
            durationLabel = block.selectFirst(".duration")?.text()?.trim()
                ?.takeIf(String::isNotBlank),
            streamUrl = null,
            source = AdultSource.Xnxx,
            embedUrl = pageUrl,
        )
    }

    private fun requestHtml(url: String): String {
        val safeUrl = normalizeXnxxRequestUrl(url)
            ?: throw IOException("XNXX returned a link outside its secure site.")
        var currentUrl = safeUrl
        for (redirectCount in 0..3) {
            var redirected = false
            var responseBody: String? = null
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", BrowserHeaders.USER_AGENT)
                .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Referer", BASE_URL)
            val providerCookies = runCatching {
                CookieManager.getInstance().getCookie(currentUrl)
            }.getOrNull()
            if (!providerCookies.isNullOrBlank()) {
                requestBuilder.header("Cookie", providerCookies)
            }
            client.newCall(requestBuilder.get().build()).execute().use { response ->
                if (response.isRedirect) {
                    if (redirectCount == 3) {
                        throw IOException("XNXX redirected too many times.")
                    }
                    currentUrl = response.request.url.resolve(response.header("Location").orEmpty())
                        ?.toString()
                        ?.let(::normalizeXnxxRequestUrl)
                        ?: throw IOException("XNXX redirected outside its secure site.")
                    redirected = true
                } else {
                    if (response.code == 429) {
                        throw IOException("XNXX is rate-limiting requests. Wait and try again.")
                    }
                    if (response.code == 403) {
                        throw IOException(
                            "XNXX denied the request or requires official verification. " +
                                "StreamCloud does not bypass those checks.",
                        )
                    }
                    if (!response.isSuccessful) {
                        throw IOException("XNXX returned HTTP ${response.code}.")
                    }
                    responseBody = response.body?.string()
                }
            }
            if (!redirected) {
                return responseBody?.takeIf(String::isNotBlank)
                    ?: throw IOException("XNXX returned an empty page.")
            }
        }
        throw IOException("XNXX redirected too many times.")
    }

    private fun parseBestArchivePrefix(html: String): String? {
        val document = Jsoup.parse(html, BASE_URL)
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val path = normalizeXnxxRequestUrl(anchor.attr("href"))
                    ?.let { it.toHttpUrl().encodedPath }
                    ?: return@mapNotNull null
                BEST_ARCHIVE_PAGE_PATTERN.find(path)?.groupValues?.getOrNull(1)
            }
            .firstOrNull()
    }

    private fun listingPageNumber(rawUrl: String, isBestListing: Boolean): Int? {
        val safeUrl = normalizeXnxxRequestUrl(rawUrl) ?: return null
        val path = safeUrl.toHttpUrl().encodedPath
        val pattern = if (isBestListing) {
            BEST_ARCHIVE_PAGE_PATTERN
        } else {
            SEARCH_PAGE_PATTERN
        }
        return pattern.find(path)?.groupValues?.lastOrNull()?.toIntOrNull()
    }

    private fun normalizeXnxxVideoPageUrl(rawUrl: String): String? {
        val normalized = normalizeXnxxRequestUrl(rawUrl) ?: return null
        val url = normalized.toHttpUrl()
        return normalized.takeIf { VIDEO_ID_PATTERN.containsMatchIn(url.encodedPath) }
    }

    private fun normalizeXnxxRequestUrl(rawUrl: String): String? {
        val value = rawUrl.trim().takeIf(String::isNotBlank) ?: return null
        val resolved = BASE_URL.toHttpUrl().resolve(value) ?: return null
        return resolved.toString().takeIf {
            resolved.scheme == "https" &&
                resolved.port == 443 &&
                resolved.host in setOf("xnxx.com", "www.xnxx.com") &&
                resolved.username.isBlank() &&
                resolved.password.isBlank()
        }
    }

    private fun normalizeXnxxImageUrl(rawUrl: String?): String? {
        val value = rawUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
        val url = runCatching {
            if (value.startsWith("//")) "https:$value".toHttpUrl()
            else BASE_URL.toHttpUrl().resolve(value)
        }.getOrNull() ?: return null
        val trustedHost = url.host == "xnxx-cdn.com" || url.host.endsWith(".xnxx-cdn.com") ||
            url.host == "xnxx.com" || url.host.endsWith(".xnxx.com")
        return url.toString().takeIf {
            url.scheme == "https" && url.port == 443 && trustedHost
        }
    }

    private fun normalizeXnxxMediaUrl(rawUrl: String): String? {
        val url = runCatching { rawUrl.toHttpUrl() }.getOrNull() ?: return null
        val trustedHost = url.host == "xnxx-cdn.com" || url.host.endsWith(".xnxx-cdn.com")
        val mediaPath = url.encodedPath.lowercase().let { path ->
            path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd")
        }
        return url.toString().takeIf {
            url.scheme == "https" && url.port == 443 && trustedHost && mediaPath
        }
    }

    private fun isVerificationPage(pageText: String): Boolean =
        listOf(
            "verify you are human",
            "checking your browser",
            "age verification required",
            "confirm you are over",
        ).any(pageText::contains)

    private val VIDEO_ID_PATTERN = Regex("^/video-([a-z0-9]+)(?:/|$)", RegexOption.IGNORE_CASE)
    private val SEARCH_PAGE_PATTERN = Regex("^/search/[^/]+/(\\d+)/?$")
    private val BEST_ARCHIVE_PAGE_PATTERN = Regex("^(/best/\\d{4}-\\d{2})/(\\d+)/?$")
}
