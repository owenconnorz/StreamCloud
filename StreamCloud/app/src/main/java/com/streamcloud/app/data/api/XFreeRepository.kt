package com.streamcloud.app.data.api

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.streamcloud.app.data.network.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

data class XFreePage(
    val items: List<AdultItem>,
    val hasMore: Boolean,
)

class XFreeUnavailableException(message: String) : Exception(message)

private const val XFREE_BASE_URL = "https://www.xfree.com"
private const val XFREE_PAGE_SIZE = 20
private val XFREE_DURATION_PATTERN =
    Regex("""(?<!\d)(?:\d{1,3}:)?\d{1,2}:\d{2}(?!\d)""")

/**
 * Reads XFree's public listing pages. Video playback remains on the official
 * video page so XFree's own player and any provider checks remain in control.
 */
object XFreeRepository {
    private val cookieHosts = listOf(
        "https://www.xfree.com",
        "https://xfree.com",
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

    suspend fun fetch(query: String, page: Int): XFreePage = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val url = buildXFreeListingUrl(query, safePage)
        parseXFreeListing(requestPage(url), url, safePage)
    }

    fun videoPageUrl(videoId: String): String {
        require(videoId.matches(Regex("\\d{1,12}"))) {
            "XFree did not provide a valid video identifier."
        }
        return "$XFREE_BASE_URL/video?id=$videoId"
    }

    /**
     * Remove only XFree cookies from WebView's shared cookie store.
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
                        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.xfree.com; Path=$path; Secure",
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

    internal fun isOfficialPageUrl(raw: String): Boolean {
        val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return false
        return url.scheme == "https" &&
            url.port == 443 &&
            (url.host == "xfree.com" || url.host.endsWith(".xfree.com"))
    }

    private fun requestPage(initialUrl: String): String {
        var url = initialUrl
        repeat(6) {
            if (!isOfficialPageUrl(url)) {
                throw XFreeUnavailableException("XFree returned an invalid page address.")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BrowserHeaders.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", BrowserHeaders.ACCEPT_LANGUAGE)
                .header("Referer", "$XFREE_BASE_URL/")
                .apply {
                    // `url` has already been restricted to XFree's HTTPS hosts;
                    // never forward this provider's cookies to a redirect target.
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie(url)
                    }.getOrNull()
                    buildXFreeCookieHeader(cookies)?.let { header("Cookie", it) }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")
                        ?: throw XFreeUnavailableException("XFree redirected to an invalid page.")
                    val nextUrl = runCatching { url.toHttpUrl().resolve(location)?.toString() }
                        .getOrNull()
                        ?: throw XFreeUnavailableException("XFree redirected to an invalid page.")
                    if (!isOfficialPageUrl(nextUrl)) {
                        throw XFreeUnavailableException(
                            "XFree sent the feed to an external page. Open that page on XFree itself, then retry.",
                        )
                    }
                    url = nextUrl
                    return@repeat
                }

                if (response.code == 403 || response.code == 429) {
                    throw XFreeUnavailableException(
                        "XFree blocked the native feed request or requested browser verification. " +
                            "Try again later or complete any required check on XFree's official site.",
                    )
                }
                if (response.code == 401) {
                    throw XFreeUnavailableException(
                        "XFree requires an official sign-in or verification before showing this feed.",
                    )
                }
                if (!response.isSuccessful) {
                    throw XFreeUnavailableException("XFree returned HTTP ${response.code}.")
                }
                return response.body?.string()
                    ?: throw XFreeUnavailableException("XFree returned an empty page.")
            }
        }
        throw XFreeUnavailableException("XFree redirected too many times.")
    }
}

/**
 * Keep XFree sign-in and playback WebViews on the same browser identity and
 * cookie policy so the provider can reuse its own authenticated/verified
 * session. This does not create or bypass any provider verification.
 */
internal fun configureXFreeWebViewSession(webView: WebView, context: Context) {
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(webView, true)
    webView.settings.userAgentString = normalizeXFreeWebViewUserAgent(
        WebSettings.getDefaultUserAgent(context),
    )
    cookies.flush()
}

internal fun normalizeXFreeWebViewUserAgent(defaultUserAgent: String): String =
    defaultUserAgent
        .replace("; wv", "")
        .replace(" Version/4.0", "")

internal fun buildXFreeCookieHeader(providerCookies: String?): String? =
    providerCookies.orEmpty()
        .split(';')
        .mapNotNull { cookie ->
            cookie.trim().takeIf {
                it.contains('=') && it.substringBefore('=').isNotBlank()
            }
        }
        .joinToString("; ")
        .takeIf(String::isNotBlank)

internal fun buildXFreeListingUrl(query: String, page: Int): String {
    val safePage = page.coerceAtLeast(1)
    val builder = if (query.isBlank()) {
        "$XFREE_BASE_URL/".toHttpUrl().newBuilder()
    } else {
        "$XFREE_BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
    }
    if (safePage > 1) builder.addQueryParameter("page", safePage.toString())
    return builder.build().toString()
}

internal fun parseXFreeListing(
    html: String,
    requestedUrl: String,
    requestedPage: Int,
): XFreePage {
    val document = Jsoup.parse(html, "$XFREE_BASE_URL/")
    val pageText = document.body()?.text().orEmpty().lowercase()
    val lowerHtml = html.lowercase()
    if (
        listOf(
            "verify you are human",
            "checking your browser",
            "access denied",
            "cf-chl-",
            "challenge-platform",
            "confirm your age",
            "age verification",
            "you must be at least 18",
        ).any { it in pageText || it in lowerHtml }
    ) {
        throw XFreeUnavailableException(
            "XFree requested browser or age verification, which the native feed cannot complete. " +
                "Use XFree's official site for that check.",
        )
    }

    val items = document.select("a[href*='/video?id='], a[href^='/video?']")
        .mapNotNull { anchor ->
            val videoUrl = normalizeXFreeVideoUrl(anchor.attr("href")) ?: return@mapNotNull null
            val parsedUrl = videoUrl.toHttpUrl()
            val id = parsedUrl.queryParameter("id") ?: return@mapNotNull null
            val containers = generateSequence(anchor as Element?) { it.parent() }
                .take(7)
                .toList()
            val card = containers.firstOrNull { container ->
                container.selectFirst("img") != null &&
                    (container.selectFirst("[data-title], [class*=title], h2, h3, h4") != null ||
                        container.selectFirst("a[href*='/tag/']") != null ||
                        container.text().length > anchor.text().length)
            } ?: containers.firstOrNull { it.selectFirst("img") != null } ?: anchor
            val title = extractXFreeTitle(anchor, containers, parsedUrl)
                ?: return@mapNotNull null
            val image = (containers.asSequence()
                .mapNotNull { it.selectFirst("img") }
                .firstOrNull())
            val thumbnail = image?.let(::extractImageUrl)
                ?.let(::normalizeXFreeAssetUrl)
            val cardText = card.text()
            val duration = XFREE_DURATION_PATTERN.find(cardText)?.value
            val views = Regex("""(?i)\b[\d,.]+\s*[kmb]?\s+views\b""")
                .find(cardText)
                ?.value
                ?.trim()
            val tags = card.select("a[href*='/tag/']")
                .map { it.text().trim() }
                .filter(String::isNotBlank)
                .distinct()
                .take(12)
                .joinToString(", ")
                .ifBlank { null }

            AdultItem(
                id = id,
                title = title,
                thumbnail = thumbnail,
                previewImage = thumbnail,
                durationLabel = duration,
                streamUrl = null,
                source = AdultSource.XFree,
                embedUrl = "$XFREE_BASE_URL/video?id=$id",
                views = views,
                tags = tags,
            )
        }
        .distinctBy(AdultItem::id)

    if (items.isEmpty()) {
        if (listOf("no videos found", "no results", "nothing was found").any { it in pageText }) {
            return XFreePage(emptyList(), hasMore = false)
        }
        throw XFreeUnavailableException(
            "XFree loaded, but its video cards could not be read. The page format may have changed.",
        )
    }

    return XFreePage(
        items = items,
        hasMore = items.isNotEmpty() &&
            (hasNextXFreePage(document, requestedUrl, requestedPage) ||
                hasMoreControl(document) ||
                items.size >= XFREE_PAGE_SIZE),
    )
}

private fun extractXFreeTitle(
    anchor: Element,
    containers: List<Element>,
    videoUrl: HttpUrl,
): String? {
    val directTitle = sequenceOf(
        anchor.attr("data-title"),
        anchor.attr("title"),
        anchor.attr("aria-label"),
    ).firstNotNullOfOrNull(::cleanXFreeTitle)
    if (directTitle != null) return directTitle

    containers.forEach { container ->
        val titleElement = container.selectFirst(
            "[data-title], [class*=title], h2, h3, h4",
        )
        val title = cleanXFreeTitle(
            titleElement?.attr("data-title").orEmpty()
                .ifBlank { titleElement?.text().orEmpty() },
        )
        if (title != null) return title
    }

    cleanXFreeTitle(anchor.text())?.let { return it }
    val slug = videoUrl.queryParameter("title")
        ?.replace(Regex("[-_]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
    return cleanXFreeTitle(slug.orEmpty())
}

private fun cleanXFreeTitle(value: String): String? =
    value.trim()
        .replace(Regex("\\s+"), " ")
        .takeIf { it.isNotBlank() && it.length <= 300 && !it.startsWith("#") }

private fun extractImageUrl(image: Element): String? {
    val raw = listOf("data-src", "data-lazy-src", "data-original", "srcset", "src")
        .firstNotNullOfOrNull { key ->
            image.attr(key).trim().takeIf {
                it.isNotBlank() && !it.startsWith("data:", ignoreCase = true)
            }
        }
        ?: return null
    return raw.substringBefore(',').trim().substringBefore(' ').trim()
}

private fun normalizeXFreeVideoUrl(raw: String): String? {
    val parsed = runCatching {
        "$XFREE_BASE_URL/".toHttpUrl().resolve(raw)
    }.getOrNull() ?: return null
    if (!XFreeRepository.isOfficialPageUrl(parsed.toString()) ||
        parsed.encodedPath != "/video"
    ) return null
    val id = parsed.queryParameter("id")?.takeIf { it.matches(Regex("\\d{1,12}")) }
        ?: return null
    return "$XFREE_BASE_URL/video?id=$id"
}

private fun normalizeXFreeAssetUrl(raw: String): String? {
    val parsed = runCatching {
        "$XFREE_BASE_URL/".toHttpUrl().resolve(raw)
    }.getOrNull() ?: return null
    if (!XFreeRepository.isOfficialPageUrl(parsed.toString())) return null
    return parsed.newBuilder().fragment(null).build().toString()
}

private fun hasNextXFreePage(
    document: org.jsoup.nodes.Document,
    requestedUrl: String,
    requestedPage: Int,
): Boolean {
    val current = runCatching { requestedUrl.toHttpUrl() }.getOrNull() ?: return false
    val nextPageLink = document.select("a[href], [data-href], [data-url]").any { element ->
        val raw = listOf("href", "data-href", "data-url")
            .firstNotNullOfOrNull { key -> element.attr(key).takeIf(String::isNotBlank) }
            ?: return@any false
        val link = runCatching { current.resolve(raw) }.getOrNull() ?: return@any false
        link.encodedPath == current.encodedPath &&
            link.queryParameter("page")?.toIntOrNull()?.let { it > requestedPage } == true
    }
    if (nextPageLink) return true

    return document.select("[data-next-page], [data-page]")
        .any { element ->
            listOf("data-next-page", "data-page")
                .firstNotNullOfOrNull { element.attr(it).toIntOrNull() }
                ?.let { it > requestedPage } == true
        }
}

private fun hasMoreControl(document: org.jsoup.nodes.Document): Boolean =
    document.select("button, a, [role=button]").any { element ->
        val label = sequenceOf(
            element.text(),
            element.attr("aria-label"),
            element.attr("title"),
        ).joinToString(" ").lowercase()
        "show more" in label || "load more" in label || Regex("\\bnext\\b").containsMatchIn(label)
    }
