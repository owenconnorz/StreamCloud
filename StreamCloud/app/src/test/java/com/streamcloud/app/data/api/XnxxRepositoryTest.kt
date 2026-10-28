package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class XnxxRepositoryTest {
    @Test
    fun searchPagesUseProviderZeroBasedPageNumbers() {
        assertEquals(
            "https://www.xnxx.com/search/indie%20rock/0",
            XnxxRepository.buildSearchUrl("indie rock", page = 1),
        )
        assertEquals(
            "https://www.xnxx.com/search/indie%20rock/1",
            XnxxRepository.buildSearchUrl("indie rock", page = 2),
        )
    }

    @Test
    fun parsesAndDeduplicatesListingCards() {
        val page = XnxxRepository.parseListing(
            html = """
                <div class="thumb-block">
                  <a href="/video-ab12cd34/sample_clip" title="Sample clip"></a>
                  <a href="/video-ab12cd34/sample_clip"></a>
                  <img data-src="//thumb-cdn77.xnxx-cdn.com/sample.jpg">
                  <span class="duration">10:20</span>
                </div>
                <a class="page-nb" href="/search/sample/1">2</a>
            """.trimIndent(),
            isBestListing = false,
            page = 1,
        )

        assertEquals(1, page.items.size)
        assertEquals("ab12cd34", page.items.single().id)
        assertEquals("Sample clip", page.items.single().title)
        assertEquals(
            "https://thumb-cdn77.xnxx-cdn.com/sample.jpg",
            page.items.single().thumbnail,
        )
        assertEquals("10:20", page.items.single().durationLabel)
        assertEquals(AdultSource.Xnxx, page.items.single().source)
        assertEquals(
            "https://www.xnxx.com/video-ab12cd34/sample_clip",
            page.items.single().embedUrl,
        )
        assertTrue(page.hasMore)
    }

    @Test
    fun parserReturnsOnlySecureOfficialCdnMediaAndPrefersHighQuality() {
        val sources = XnxxRepository.parseXnxxStreamSources(
            """
                html5player.setVideoUrlHigh('https://mp4-cdn77.xnxx-cdn.com/a/sample.mp4?token=1');
                html5player.setVideoHLS('https://hls-cdn77.xnxx-cdn.com/a/sample.m3u8?token=2');
                html5player.setVideoUrlLow('https://mp4-cdn77.xnxx-cdn.com/a/low.mp4?token=3');
                html5player.setVideoUrlLow('http://untrusted.example/low.mp4');
            """.trimIndent(),
        )

        assertEquals(listOf("High", "HLS", "Low"), sources.map(XnxxStreamSource::quality))
        assertEquals(
            "https://mp4-cdn77.xnxx-cdn.com/a/sample.mp4?token=1",
            sources.first().url,
        )
        assertTrue(sources.all { it.url.startsWith("https://") })
        assertTrue(sources.all { it.url.contains(".xnxx-cdn.com/") })
        assertFalse(sources.any { it.url.contains("untrusted.example") })
    }

    @Test
    fun verificationPagesAreNotTreatedAsVideoLists() {
        val error = runCatching {
            XnxxRepository.parseListing(
                html = "<html><body>Checking your browser before accessing this site</body></html>",
                isBestListing = true,
                page = 1,
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("does not bypass"))
    }
}
