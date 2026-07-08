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
}
