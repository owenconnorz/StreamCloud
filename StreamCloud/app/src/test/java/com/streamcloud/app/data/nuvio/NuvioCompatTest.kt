package com.streamcloud.app.data.nuvio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioCompatTest {

    @Test
    fun canonicalizesTmdbMirrorUrlsToOfficialHost() {
        assertEquals(
            "https://api.themoviedb.org/3/movie/687163?append_to_response=external_ids",
            tmdbMirrorFallbackUrl("https://db.videasy.to/3/movie/687163?append_to_response=external_ids"),
        )
    }

    @Test
    fun injectsBrowserStyleDefaultsForNuvioRequests() {
        val headers = buildNuvioRequestHeaders(
            requestUrl = "https://vidfast.pro/movie/687163",
            method = "GET",
            incoming = emptyMap(),
        )

        assertEquals(NUVIO_DEFAULT_USER_AGENT, headers["User-Agent"])
        assertEquals("https://vidfast.pro/", headers["Referer"])
        assertEquals("same-origin", headers["Sec-Fetch-Site"])
        assertEquals("gzip, deflate", headers["Accept-Encoding"])
    }

    @Test
    fun summarizesDiagnosticsWithPhaseStatusAndHost() {
        val summary = NuvioProviderDiagnostics(
            phase = "request",
            requestCount = 2,
            lastDomain = "vidsrc.me",
            lastStatus = 403,
            errorSummary = "HTTP 403 from https://vidsrc.me/embed/687163",
        ).toSummary()

        assertTrue(summary.contains("HTTP 403"))
        assertTrue(summary.contains("phase=request"))
        assertTrue(summary.contains("status=403"))
        assertTrue(summary.contains("host=vidsrc.me"))
    }

    // ── URL scheme sanitization (VidFast nttps:// fix) ───────────────────────

    @Test
    fun fixesNttpsSchemeTypo() {
        assertEquals(
            "https://vidfast.vc/movie/1108427/",
            sanitizeNuvioUrlScheme("nttps://vidfast.vc/movie/1108427/"),
        )
    }

    @Test
    fun fixesHtpsSchemeTypo() {
        assertEquals(
            "https://example.com/path",
            sanitizeNuvioUrlScheme("htps://example.com/path"),
        )
    }

    @Test
    fun fixesHttsSchemeTypo() {
        assertEquals(
            "https://example.com/path",
            sanitizeNuvioUrlScheme("htts://example.com/path"),
        )
    }

    @Test
    fun fixesHttttpSchemeTypo() {
        assertEquals(
            "http://example.com/path",
            sanitizeNuvioUrlScheme("htttp://example.com/path"),
        )
    }

    @Test
    fun leavesValidHttpsUrlUnchanged() {
        val url = "https://vidfast.vc/movie/1108427/"
        assertEquals(url, sanitizeNuvioUrlScheme(url))
    }

    @Test
    fun leavesValidHttpUrlUnchanged() {
        val url = "http://example.com/api/v1/stream"
        assertEquals(url, sanitizeNuvioUrlScheme(url))
    }

    @Test
    fun returnsBlankUrlUnchanged() {
        assertEquals("", sanitizeNuvioUrlScheme(""))
    }

    // ── BOM stripping ────────────────────────────────────────────────────────

    @Test
    fun stripsLeadingBomFromResponseBody() {
        val body = "\uFEFF{\"id\":1}"
        assertEquals("{\"id\":1}", body.stripLeadingBom())
    }

    @Test
    fun leavesBodyWithoutBomUnchanged() {
        val body = "{\"id\":1}"
        assertEquals(body, body.stripLeadingBom())
    }

    @Test
    fun stripsOnlyOneLeadingBom() {
        // Only the very first BOM should be stripped; a BOM inside the string stays.
        val body = "\uFEFF{\"key\":\"\uFEFFvalue\"}"
        assertEquals("{\"key\":\"\uFEFFvalue\"}", body.stripLeadingBom())
    }

    @Test
    fun stripsLeadingBomFromEmptyish() {
        assertEquals("", "\uFEFF".stripLeadingBom())
    }
}
