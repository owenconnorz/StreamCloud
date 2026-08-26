package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubRepositoryTest {

    @Test
    fun parsesVideoCardsFromPublicListingMarkup() {
        val html = """
            <ul id="videoListSearchResults">
              <li class="videoSearchList_test" data-video-vkey="abc123">
                <div>
                  <a href="/view_video.php?viewkey=abc123" data-webm="https://cdn.example/preview.webm">
                    <img data-src="//images.example/abc.jpg" alt="Fallback title">
                  </a>
                  <div class="title"><a href="/view_video.php?viewkey=abc123">Example title</a></div>
                  <span class="duration">12:34</span>
                  <div class="views">1.2M Views</div>
                  <a class="uploaderLink">Example channel</a>
                </div>
              </li>
            </ul>
        """.trimIndent()

        val items = parsePornhubListing(html)

        assertEquals(1, items.size)
        assertEquals("abc123", items.single().id)
        assertEquals("Example title", items.single().title)
        assertEquals("https://images.example/abc.jpg", items.single().thumbnail)
        assertEquals("12:34", items.single().durationLabel)
        assertEquals(AdultSource.Pornhub, items.single().source)
    }

    @Test
    fun parsesMediaDefinitionsAndPrefersAdaptiveHlsForPlayback() {
        val html = """
            <script>
              window.player = {
                "mediaDefinitions": [
                  {"format":"mp4","quality":"720","videoUrl":"https:\/\/cdn.example\/video-720.mp4?x=1\u0026y=2"},
                  {"format":"hls","quality":"1080","videoUrl":"https:\/\/cdn.example\/master.m3u8"}
                ]
              };
            </script>
        """.trimIndent()

        val sources = parsePornhubMediaDefinitions(html)

        assertEquals(2, sources.size)
        assertEquals(
            "https://cdn.example/master.m3u8",
            choosePornhubSource(sources)?.url,
        )
        assertEquals(
            "https://cdn.example/video-720.mp4?x=1&y=2",
            choosePornhubSource(sources, preferProgressive = true)?.url,
        )
    }

    @Test
    fun detectsExplicitNextPageLink() {
        assertTrue(
            hasNextPornhubPage(
                """<a class="page_next" href="/video?page=2">Next</a>""",
                currentPage = 1,
            ),
        )
        assertFalse(
            hasNextPornhubPage(
                """<main>No videos found</main>""",
                currentPage = 3,
            ),
        )
    }

    @Test
    fun cookieParsingKeepsOnlyPornhubCookieNames() {
        assertEquals(
            setOf("session", "accessAgeDisclaimerPH", "platform"),
            pornhubCookieNames("session=abc; accessAgeDisclaimerPH=1; platform=mobile"),
        )
        assertTrue(
            pornhubCookieNames("accessAgeDisclaimerPH=1; platform=mobile")
                .none { it == "session" },
        )
    }

    @Test
    fun allowsOnlyOfficialHttpsPornhubUrls() {
        assertTrue(isAllowedPornhubUrl("https://www.pornhub.com/view_video.php?viewkey=abc"))
        assertTrue(isAllowedPornhubUrl("https://cdn.pornhub.com/video/master.m3u8"))
        assertFalse(isAllowedPornhubUrl("http://www.pornhub.com/login"))
        assertFalse(isAllowedPornhubUrl("https://pornhub.com.evil.example/video"))
        assertFalse(isAllowedPornhubUrl("https://evil.example/pornhub.com/video"))
    }

    @Test
    fun rejectsRedirectsOutsidePornhubDomains() {
        val start = "https://www.pornhub.com/login"
        assertEquals(
            "https://www.pornhub.com/video",
            resolveAllowedPornhubRedirect(start, "/video"),
        )
        assertEquals(
            "https://m.pornhub.com/video",
            resolveAllowedPornhubRedirect(start, "https://m.pornhub.com/video"),
        )
        assertEquals(null, resolveAllowedPornhubRedirect(start, "https://evil.example/collect"))
    }
}