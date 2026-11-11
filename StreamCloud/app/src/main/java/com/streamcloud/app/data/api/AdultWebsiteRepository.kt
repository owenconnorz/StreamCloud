package com.streamcloud.app.data.api

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.streamcloud.app.data.network.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class AdultWebsitePage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

data class AdultWebsitePlayback(
    val url: String,
    val headers: Map<String, String>,
    val isHls: Boolean = false,
)

/**
 * Reads public catalog pages and provider-published native streams from
 * Porn4Days, PornHD3x, and PornSlash. It does not derive stream URLs or bypass
 * verification.
 */
object AdultWebsiteRepository {
    private const val PORN4DAYS_BASE_URL = "https://porn4days.pw/"
    private const val PORNHD3X_BASE_URL = "https://www.pornhd3x.tv/"
    private const val PORNSLASH_BASE_URL = "https://www.pornslash.com/"
    private const val PORN4DAYS_PAGE_SIZE = 24
    private const val PORNHD3X_PAGE_SIZE = 40

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private val playbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun supports(source: AdultSource): Boolean =
        source == AdultSource.Porn4Days ||
            source == AdultSource.PornHd3x ||
            source == AdultSource.PornSlash

    internal fun buildPageUrl(source: AdultSource, query: String, page: Int): String {
        require(page > 0) { "Page must be positive." }
        val cleanQuery = query.trim()
        val builder = baseUrl(source).toHttpUrl().newBuilder()
        when (source) {
            AdultSource.Porn4Days -> {
                if (cleanQuery.isBlank()) {
                    builder.addPathSegment("newest")
                    if (page > 1) builder.addPathSegment("page$page")
                } else {
                    builder.addPathSegment("search")
                    if (page > 1) builder.addPathSegment("page$page")
                    builder.addQueryParameter("s", cleanQuery)
                }
            }

            AdultSource.PornHd3x -> {
                if (cleanQuery.isBlank()) {
                    builder.addPathSegment("premium-porn-hd")
                    builder.addPathSegment("page-$page")
                } else {
                    builder.addPathSegment("search")
                    builder.addPathSegment(searchSlug(cleanQuery))
                    builder.addPathSegment("page-$page")
                }
            }

            AdultSource.PornSlash -> {
                if (cleanQuery.isNotBlank()) {
                    builder.addPathSegment("search")
                    builder.addPathSegment(cleanQuery.replace(Regex("\\s+"), "+"))
                }
                if (page > 1) builder.addQueryParameter("p", page.toString())
            }

            else -> error("Unsupported adult website source: $source")
        }
        return builder.build().toString()
    }

    internal fun browserUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context.applicationContext)
            .replace("; wv", "")
            .replace(" Version/4.0", "")

    suspend fun resolvePlayback(
        source: AdultSource,
        pageUrl: String,
        userAgent: String,
    ): AdultWebsitePlayback = withContext(Dispatchers.IO) {
        require(supports(source)) { "Unsupported adult website source: $source" }
        val safePageUrl = normalizePageUrl(source, pageUrl)
            ?: throw IOException("${source.label} did not provide a valid video page.")
        val html = requestOfficialVideoPage(source, safePageUrl, userAgent)
        val streamUrl = when (source) {
            AdultSource.Porn4Days -> parsePorn4DaysStreamUrl(html)
            AdultSource.PornHd3x -> parsePornHd3xStreamUrl(html)
            AdultSource.PornSlash -> parsePornSlashStreamUrl(html)
            else -> null
        } ?: throw IOException(
            when (source) {
                AdultSource.PornHd3x ->
                    "PornHD3x did not publish a supported direct stream on its video page. " +
                        "Use its official page player instead."
                AdultSource.PornSlash ->
                    "PornSlash did not publish a supported native stream on its video page."
                else ->
                    "${source.label} did not publish a supported native stream. " +
                        "Open the official page to check whether verification is required."
            },
        )

        AdultWebsitePlayback(
            url = streamUrl,
            headers = buildPlaybackHeaders(source, safePageUrl, userAgent),
            isHls = source == AdultSource.PornSlash,
        )
    }

    internal fun parsePorn4DaysStreamUrl(html: String): String? {
        val rawUrl = Regex(
            """SERVER1_URL\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&", ignoreCase = true)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return normalizeStreamUrl(AdultSource.Porn4Days, rawUrl)
    }

    internal fun parsePornHd3xStreamUrl(html: String): String? {
        val jwPlayerSetups = Regex(
            """(?is)jwplayer\s*\([^)]*\)\s*\.setup\s*\((.*?)\)\s*;""",
        ).findAll(html)
        val sources = jwPlayerSetups.flatMap { setup ->
            Regex(
                """(?is)["']?file["']?\s*:\s*["']([^"']+)["']""",
            ).findAll(setup.groupValues[1]).map { it.groupValues[1] }
        }
        return sources.firstNotNullOfOrNull { rawUrl ->
            normalizeStreamUrl(
                AdultSource.PornHd3x,
                rawUrl
                    .replace("\\/", "/")
                    .replace("\\u0026", "&", ignoreCase = true)
                    .replace("&amp;", "&", ignoreCase = true),
            )
        }
    }

    internal fun parsePornSlashStreamUrl(html: String): String? =
        Regex(
            """(?is)\.loadSource\(\s*["'](https://[a-z0-9-]+\.trycloudflare\.com/master/[A-Za-z0-9_-]+)["']\s*\)""",
            RegexOption.IGNORE_CASE,
        ).findAll(html)
            .map { it.groupValues[1] }
            .firstNotNullOfOrNull(::normalizePornSlashStreamUrl)

    internal fun normalizePornSlashStreamUrl(rawUrl: String): String? {
        val cleaned = rawUrl.trim()
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val allowedHost = Regex("""[a-z0-9-]+\.trycloudflare\.com""").matches(host)
        val allowedPath = Regex("""/master/[A-Za-z0-9_-]+""")
            .matches(uri.path.orEmpty())
        return cleaned.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                uri.query == null &&
                uri.fragment == null &&
                allowedHost &&
                allowedPath
        }
    }

    internal fun normalizeStreamUrl(source: AdultSource, rawUrl: String): String? {
        if (!supports(source)) return null
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val allowedHost = when (source) {
            AdultSource.Porn4Days -> host == "video.amateur4days.com"
            AdultSource.PornHd3x ->
                host in setOf("www.pornhd3x.tv", "pornhd3x.tv", "prog-public-ec.project1content.com")
            else -> false
        }
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        val allowedExtension = when (source) {
            AdultSource.Porn4Days -> extension == "m3u8"
            AdultSource.PornHd3x -> extension in setOf("mp4", "m3u8")
            else -> false
        }
        return rawUrl.trim().takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                allowedHost &&
                allowedExtension
        }
    }

    private fun requestOfficialVideoPage(
        source: AdultSource,
        pageUrl: String,
        userAgent: String,
    ): String {
        var currentUrl = pageUrl
        for (redirectCount in 0..3) {
            var redirectedUrl: String? = null
            var pageHtml: String? = null
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
                .header("Referer", baseUrl(source))
            runCatching { CookieManager.getInstance().getCookie(currentUrl) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { requestBuilder.header("Cookie", it) }

            playbackClient.newCall(requestBuilder.get().build()).execute().use { response ->
                if (response.isRedirect) {
                    if (redirectCount == 3) {
                        throw IOException("${source.label} redirected too many times.")
                    }
                    redirectedUrl = response.request.url.resolve(response.header("Location").orEmpty())
                        ?.toString()
                        ?.let { normalizePageUrl(source, it) }
                        ?: throw IOException("${source.label} redirected outside its official site.")
                } else {
                    if (response.code == 403) {
                        throw IOException(
                            "${source.label} requires official browser or age verification. " +
                                "Complete it on the provider page, then retry.",
                        )
                    }
                    if (response.code == 429) {
                        throw IOException("${source.label} is rate-limiting playback requests. Try again later.")
                    }
                    if (!response.isSuccessful) {
                        throw IOException("${source.label} could not prepare playback (HTTP ${response.code}).")
                    }
                    pageHtml = response.body?.string()
                        ?.takeIf(String::isNotBlank)
                        ?: throw IOException("${source.label} returned an empty video page.")
                }
            }
            val nextUrl = redirectedUrl
            if (nextUrl != null) {
                currentUrl = nextUrl
                continue
            }
            val html = checkNotNull(pageHtml)
            ensureNoVerificationPage(source, html)
            return html
        }
        throw IOException("${source.label} redirected too many times.")
    }

    private fun ensureNoVerificationPage(source: AdultSource, html: String) {
        val pageText = Jsoup.parse(html).text().lowercase()
        if (
            listOf(
                "checking your browser",
                "verify you are human",
                "confirm you are human",
                "complete the captcha",
            ).any(pageText::contains)
        ) {
            throw IOException(
                "${source.label} requires its official browser verification. " +
                    "Complete it on the provider page, then retry.",
            )
        }
    }

    private fun buildPlaybackHeaders(
        source: AdultSource,
        pageUrl: String,
        userAgent: String,
    ): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to pageUrl,
        "Origin" to baseUrl(source).trimEnd('/'),
        "Accept" to "*/*",
    )

    suspend fun fetchPage(source: AdultSource, query: String, page: Int): AdultWebsitePage =
        withContext(Dispatchers.IO) {
            require(supports(source)) { "Unsupported adult website source: $source" }
            val pageUrl = buildPageUrl(source, query, page)
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", BrowserHeaders.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", baseUrl(source))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    throw IOException(
                        "${source.label} requires its official browser or age verification. " +
                            "Open the provider page and complete its check, then retry.",
                    )
                }
                if (response.code == 429) {
                    throw IOException("${source.label} is rate-limiting requests. Try again later.")
                }
                if (!response.isSuccessful) {
                    throw IOException("${source.label} returned HTTP ${response.code}.")
                }
                val html = response.body?.string()
                    ?: throw IOException("${source.label} returned an empty page.")
                val pageText = Jsoup.parse(html).text().lowercase()
                if (
                    listOf(
                        "checking your browser",
                        "verify you are human",
                        "confirm you are human",
                        "complete the captcha",
                    ).any(pageText::contains)
                ) {
                    throw IOException(
                        "${source.label} requires its official browser verification. " +
                            "Open the provider page, complete the check, and browse there.",
                    )
                }
                parseListingHtml(source, html, response.request.url.toString(), page)
            }
        }

    internal fun parseListingHtml(
        source: AdultSource,
        html: String,
        pageUrl: String,
        page: Int,
    ): AdultWebsitePage {
        require(supports(source)) { "Unsupported adult website source: $source" }
        val document = Jsoup.parse(html, pageUrl)
        val items = when (source) {
            AdultSource.Porn4Days -> parsePorn4DaysItems(document)
            AdultSource.PornHd3x -> parsePornHd3xItems(document)
            AdultSource.PornSlash -> parsePornSlashItems(document)
            else -> emptyList()
        }.distinctBy(AdultItem::id)
        val nextPage = when (source) {
            AdultSource.Porn4Days -> document.select("a.page-link[href]")
                .mapNotNull { pageNumberFromPorn4DaysUrl(it.absUrl("href")) }
                .any { it > page }

            AdultSource.PornHd3x -> document.select("a[href*=\"/page-\"]")
                .mapNotNull { pageNumberFromPornHd3xUrl(it.absUrl("href")) }
                .any { it > page }

            AdultSource.PornSlash -> document.select(".pager a[href]")
                .mapNotNull { pageNumberFromPornSlashUrl(it.absUrl("href")) }
                .any { it > page }

            else -> false
        }
        val pageSize = when (source) {
            AdultSource.Porn4Days -> PORN4DAYS_PAGE_SIZE
            AdultSource.PornHd3x -> PORNHD3X_PAGE_SIZE
            AdultSource.PornSlash -> 0
            else -> 0
        }
        return AdultWebsitePage(
            items = items,
            hasMore = if (source == AdultSource.PornSlash) {
                items.isNotEmpty() && nextPage
            } else {
                items.isNotEmpty() && (nextPage || items.size >= pageSize)
            },
        )
    }

    internal fun normalizePageUrl(source: AdultSource, rawUrl: String): String? {
        if (!supports(source)) return null
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return rawUrl.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                isAllowedPageHost(source, host) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }
    }

    internal fun isAllowedMainFrameUrl(source: AdultSource, rawUrl: String): Boolean =
        normalizePageUrl(source, rawUrl) != null ||
            (source == AdultSource.PornHd3x && isAllowedPornHd3xPlayerUrl(rawUrl))

    private fun isAllowedPornHd3xPlayerUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val allowedHost = listOf("lulustream.com", "doodstream.co").any { domain ->
            host == domain || host.endsWith(".$domain")
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            allowedHost
    }

    private fun parsePorn4DaysItems(document: Document): List<AdultItem> =
        document.select(".video-card").mapNotNull { card ->
            val pageLink = card.selectFirst("a.video-thumb[href]") ?: return@mapNotNull null
            val pageUrl = normalizePageUrl(AdultSource.Porn4Days, pageLink.absUrl("href"))
                ?: return@mapNotNull null
            val image = pageLink.selectFirst("img")
            val title = card.selectFirst("a.theme-link")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank { pageLink.attr("title").trim() }
                .ifBlank { image?.attr("alt").orEmpty().trim() }
                .ifBlank { titleFromUrl(pageUrl) }
            item(AdultSource.Porn4Days, pageUrl, title, image)
        }

    private fun parsePornHd3xItems(document: Document): List<AdultItem> =
        document.select(".ml-item").mapNotNull { card ->
            val pageLink = card.selectFirst("a.ml-mask.jt[href]")
                ?: card.selectFirst("a.ml-mask[href]")
                ?: return@mapNotNull null
            val pageUrl = normalizePageUrl(AdultSource.PornHd3x, pageLink.absUrl("href"))
                ?: return@mapNotNull null
            val image = pageLink.selectFirst("img")
            val title = pageLink.attr("title")
                .trim()
                .ifBlank { card.selectFirst(".mli-info")?.text()?.trim().orEmpty() }
                .ifBlank { image?.attr("alt").orEmpty().trim() }
                .ifBlank { titleFromUrl(pageUrl) }
            item(AdultSource.PornHd3x, pageUrl, title, image)
        }

    private fun parsePornSlashItems(document: Document): List<AdultItem> =
        document.select(".video-item").mapNotNull { card ->
            val info = card.selectFirst(".video-info") ?: return@mapNotNull null
            val pageUrl = normalizePageUrl(
                AdultSource.PornSlash,
                card.selectFirst("a[href*=\"/watch/\"]")?.absUrl("href").orEmpty(),
            ) ?: return@mapNotNull null
            val id = info.attr("data-id").trim()
                .ifBlank {
                    runCatching { URI(pageUrl).path.orEmpty().substringAfterLast('/') }
                        .getOrDefault("")
                }
                .takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,80}")) }
                ?: return@mapNotNull null
            val thumbnailElement = card.selectFirst(".thumbnail")
            val image = thumbnailElement?.selectFirst("img")
            val title = info.attr("data-title").trim()
                .ifBlank { image?.attr("alt").orEmpty().trim() }
                .ifBlank { card.selectFirst(".video-title")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null

            AdultItem(
                id = id,
                title = title,
                thumbnail = image?.let { img -> secureImageUrl(img.absUrl("src")) },
                previewImage = image?.let { img -> secureImageUrl(img.absUrl("src")) },
                durationLabel = thumbnailElement?.selectFirst(".duration")?.text()?.trim(),
                streamUrl = null,
                source = AdultSource.PornSlash,
                embedUrl = pageUrl,
                rating = info.attr("data-quality").takeIf(String::isNotBlank),
            )
        }

    private fun item(
        source: AdultSource,
        pageUrl: String,
        title: String,
        image: Element?,
    ): AdultItem? {
        val id = runCatching { URI(pageUrl).path.orEmpty().trimEnd('/').substringAfterLast('/') }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (title.isBlank()) return null

        val thumbnail = image?.let { element ->
            listOf("data-original", "data-src", "data-lazy-src", "src")
                .firstNotNullOfOrNull { attribute ->
                    element.absUrl(attribute).takeIf(String::isNotBlank)
                }
        }?.let(::secureImageUrl)

        return AdultItem(
            id = id,
            title = title,
            thumbnail = thumbnail,
            previewImage = thumbnail,
            durationLabel = null,
            streamUrl = null,
            source = source,
            embedUrl = pageUrl,
        )
    }

    private fun secureImageUrl(rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        return rawUrl.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }
    }

    private fun titleFromUrl(pageUrl: String): String =
        runCatching { URI(pageUrl).path.orEmpty().trimEnd('/').substringAfterLast('/') }
            .getOrDefault("")
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()

    private fun pageNumberFromPorn4DaysUrl(rawUrl: String): Int? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        return Regex("/page(\\d+)/?$").find(uri.path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: uri.rawQuery
                ?.split('&')
                ?.firstOrNull { it.substringBefore('=') == "page" }
                ?.substringAfter('=')
                ?.toIntOrNull()
            ?: 1
    }

    private fun pageNumberFromPornHd3xUrl(rawUrl: String): Int? {
        val path = runCatching { URI(rawUrl).path }.getOrNull() ?: return null
        return Regex("/page-(\\d+)/?$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun pageNumberFromPornSlashUrl(rawUrl: String): Int? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (normalizePageUrl(AdultSource.PornSlash, rawUrl) == null) return null
        return uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "p" }
            ?.substringAfter('=')
            ?.toIntOrNull()
    }

    private fun searchSlug(query: String): String =
        query.trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "-")
            .trim('-')
            .ifBlank { "search" }

    private fun baseUrl(source: AdultSource): String = when (source) {
        AdultSource.Porn4Days -> PORN4DAYS_BASE_URL
        AdultSource.PornHd3x -> PORNHD3X_BASE_URL
        AdultSource.PornSlash -> PORNSLASH_BASE_URL
        else -> error("Unsupported adult website source: $source")
    }

    private fun allowedHosts(source: AdultSource): Set<String> = when (source) {
        AdultSource.Porn4Days -> setOf("porn4days.pw", "www.porn4days.pw")
        AdultSource.PornHd3x -> setOf("pornhd3x.tv", "www.pornhd3x.tv")
        AdultSource.PornSlash -> setOf("pornslash.com", "www.pornslash.com")
        else -> emptySet()
    }

    private fun isAllowedPageHost(source: AdultSource, host: String): Boolean =
        host in allowedHosts(source) ||
            (source == AdultSource.PornHd3x && Regex("""www\d+\.pornhd3x\.tv""").matches(host))
}
