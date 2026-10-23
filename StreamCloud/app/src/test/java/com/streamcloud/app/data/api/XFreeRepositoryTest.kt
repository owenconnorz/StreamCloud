package com.streamcloud.app.data.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XFreeRepositoryTest {
    @Test
    fun listingParserExtractsCardsAndOfficialPagination() {
        val html = """
            <html><body>
              <div class="video-card">
                <a href="/video?id=12345&amp;title=Sample-Clip">
                  <img src="https://thumbs.xfree.com/listing/medium/sample_0.webp"
                       alt="sample thumbnail">
                  <span class="duration">02:34</span>
                </a>
                <div class="video-title">Sample clip</div>
                <a href="/tag/example">Example</a>
                <span>1.2M views</span>
              </div>
              <a rel="next" href="/?page=2">Next</a>
            </body></html>
        """.trimIndent()

        val page = parseXFreeListing(
            html = html,
            requestedUrl = "https://www.xfree.com/".toHttpUrl().toString(),
            requestedPage = 1,
        )

        assertEquals(1, page.items.size)
        assertEquals("12345", page.items.single().id)
        assertEquals("Sample clip", page.items.single().title)
        assertEquals("02:34", page.items.single().durationLabel)
        assertEquals("https://thumbs.xfree.com/listing/medium/sample_0.webp", page.items.single().thumbnail)
        assertEquals(AdultSource.XFree, page.items.single().source)
        assertEquals("https://www.xfree.com/video?id=12345", page.items.single().embedUrl)
        assertEquals("1.2M views", page.items.single().views)
        assertEquals("Example", page.items.single().tags)
        assertTrue(page.hasMore)
    }

    @Test
    fun listingUrlEncodesSearchAndPagination() {
        val url = buildXFreeListingUrl("sample query", 3).toHttpUrl()

        assertEquals("/search", url.encodedPath)
        assertEquals("sample query", url.queryParameter("q"))
        assertEquals("3", url.queryParameter("page"))
    }

    @Test
    fun cookieHeaderKeepsOnlyWellFormedProviderCookiePairs() {
        assertEquals(
            "session=example; preference=dark",
            buildXFreeCookieHeader(" session=example; ; preference=dark; "),
        )
        assertNull(buildXFreeCookieHeader(" ; ; "))
    }

    @Test
    fun loginAndPlaybackCanShareTheSameNormalizedBrowserIdentity() {
        val defaultUserAgent =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36; wv"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36",
            normalizeXFreeWebViewUserAgent(defaultUserAgent),
        )
    }

    @Test
    fun parserRejectsExternalCardsAndInvalidVideoIds() {
        val html = """
            <html><body>
              <div class="video-card">
                <a href="https://outside.example/video?id=99&amp;title=External">
                  <img src="https://thumbs.xfree.com/listing/external.webp">
                </a>
                <div class="video-title">External card</div>
              </div>
              <div class="video-card">
                <a href="/video?id=bad&amp;title=Invalid">
                  <img src="https://thumbs.xfree.com/listing/invalid.webp">
                </a>
                <div class="video-title">Invalid card</div>
              </div>
              <p>No videos found</p>
            </body></html>
        """.trimIndent()

        val page = parseXFreeListing(html, "https://www.xfree.com/search?q=none", 1)

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun verificationPagesAreReportedWithoutBypassingThem() {
        val error = runCatching {
            parseXFreeListing(
                html = "<html><body>Checking your browser before accessing this site</body></html>",
                requestedUrl = "https://www.xfree.com/",
                requestedPage = 1,
            )
        }.exceptionOrNull()

        assertTrue(error is XFreeUnavailableException)
        assertTrue(error?.message.orEmpty().contains("verification"))
    }

    @Test
    fun officialPageValidationRejectsNonXFreeHostsAndHttp() {
        assertTrue(XFreeRepository.isOfficialPageUrl("https://www.xfree.com/video?id=123"))
        assertTrue(XFreeRepository.isOfficialPageUrl("https://thumbs.xfree.com/listing/thumb.webp"))
        assertFalse(XFreeRepository.isOfficialPageUrl("http://www.xfree.com/video?id=123"))
        assertFalse(XFreeRepository.isOfficialPageUrl("https://xfree.com.example/video?id=123"))
        assertNull(runCatching { XFreeRepository.videoPageUrl("../123") }.getOrNull())
    }
}
