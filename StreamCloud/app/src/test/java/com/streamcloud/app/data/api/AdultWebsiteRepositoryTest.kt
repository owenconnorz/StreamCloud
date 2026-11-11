package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultWebsiteRepositoryTest {
    @Test
    fun buildsPagedAndSearchUrlsForSupportedSites() {
        assertEquals(
            "https://porn4days.pw/newest/page2",
            AdultWebsiteRepository.buildPageUrl(AdultSource.Porn4Days, query = "", page = 2),
        )
        assertEquals(
            "https://porn4days.pw/search/page2?s=red%20fox",
            AdultWebsiteRepository.buildPageUrl(AdultSource.Porn4Days, query = "red fox", page = 2),
        )
        assertEquals(
            "https://www.pornhd3x.tv/premium-porn-hd/page-3",
            AdultWebsiteRepository.buildPageUrl(AdultSource.PornHd3x, query = "", page = 3),
        )
        assertEquals(
            "https://www.pornhd3x.tv/search/red-fox/page-2",
            AdultWebsiteRepository.buildPageUrl(AdultSource.PornHd3x, query = "red fox", page = 2),
        )
        assertEquals(
            "https://www.pornslash.com/?p=2",
            AdultWebsiteRepository.buildPageUrl(AdultSource.PornSlash, query = "", page = 2),
        )
        assertEquals(
            "https://www.pornslash.com/search/red+fox?p=2",
            AdultWebsiteRepository.buildPageUrl(AdultSource.PornSlash, query = "red fox", page = 2),
        )
    }

    @Test
    fun parsesPorn4DaysCardsAndPagination() {
        val page = AdultWebsiteRepository.parseListingHtml(
            source = AdultSource.Porn4Days,
            pageUrl = "https://porn4days.pw/newest",
            page = 1,
            html = """
                <div class="video-card">
                  <a class="video-thumb" href="/video/sample-video">
                    <img src="/img3/sample.jpg" alt="Sample video">
                  </a>
                  <a class="theme-link" href="/video/sample-video">Sample title</a>
                </div>
                <a class="page-link" href="/newest/page2/">2</a>
            """.trimIndent(),
        )

        assertEquals(AdultSource.Porn4Days, page.items.single().source)
        assertEquals("sample-video", page.items.single().id)
        assertEquals("Sample title", page.items.single().title)
        assertEquals("https://porn4days.pw/img3/sample.jpg", page.items.single().thumbnail)
        assertEquals("https://porn4days.pw/video/sample-video", page.items.single().embedUrl)
        assertTrue(page.hasMore)
    }

    @Test
    fun ignoresPathlessPorn4DaysLinksWithoutCrashing() {
        val page = AdultWebsiteRepository.parseListingHtml(
            source = AdultSource.Porn4Days,
            pageUrl = "https://porn4days.pw/newest",
            page = 1,
            html = """
                <div class="video-card">
                  <a class="video-thumb" href="https://porn4days.pw">
                    <img src="/img3/sample.jpg" alt="Missing video path">
                  </a>
                  <a class="theme-link" href="https://porn4days.pw">Missing video path</a>
                </div>
                <a class="page-link" href="https://porn4days.pw">Next</a>
            """.trimIndent(),
        )

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun parsesPornHd3xCardsAndPagination() {
        val page = AdultWebsiteRepository.parseListingHtml(
            source = AdultSource.PornHd3x,
            pageUrl = "https://www.pornhd3x.tv/premium-porn-hd/page-1",
            page = 1,
            html = """
                <div class="ml-item">
                  <a class="ml-mask jt" href="/video/sample-video" title="HD sample title">
                    <img class="thumb__img" data-original="/uploads/sample.jpg">
                  </a>
                </div>
                <a href="/premium-porn-hd/page-2">2</a>
            """.trimIndent(),
        )

        assertEquals(AdultSource.PornHd3x, page.items.single().source)
        assertEquals("sample-video", page.items.single().id)
        assertEquals("HD sample title", page.items.single().title)
        assertEquals("https://www.pornhd3x.tv/uploads/sample.jpg", page.items.single().thumbnail)
        assertEquals("https://www.pornhd3x.tv/video/sample-video", page.items.single().embedUrl)
        assertTrue(page.hasMore)
    }

    @Test
    fun parsesPornSlashCatalogWithoutTreatingHoverPreviewsAsStreams() {
        val page = AdultWebsiteRepository.parseListingHtml(
            source = AdultSource.PornSlash,
            pageUrl = "https://www.pornslash.com/",
            page = 1,
            html = """
                <div class="video-item">
                  <a href="/watch/Abc123456">
                    <div class="thumbnail"
                         data-p="https://wolfcipher56-1343.pages.dev/13426/p/PreviewOnly.mp4">
                      <img src="https://wolfcipher56-1343.pages.dev/13426/t/PreviewOnly.jpeg"
                           alt="Sample PornSlash video">
                      <div class="duration">38:30</div>
                    </div>
                  </a>
                  <div class="video-info" data-id="Abc123456"
                       data-title="Sample PornSlash video" data-quality="1080"></div>
                </div>
                <div class="pager">
                  <a href="/?p=1"><span>1</span></a>
                  <a href="/?p=2"><span>2</span></a>
                </div>
            """.trimIndent(),
        )

        val item = page.items.single()
        assertEquals(AdultSource.PornSlash, item.source)
        assertEquals("Abc123456", item.id)
        assertEquals("Sample PornSlash video", item.title)
        assertEquals("38:30", item.durationLabel)
        assertNull(item.streamUrl)
        assertEquals("https://www.pornslash.com/watch/Abc123456", item.embedUrl)
        assertTrue(page.hasMore)
    }

    @Test
    fun parsesOnlyThePornSlashOfficialPlayerHlsSource() {
        val stream = AdultWebsiteRepository.parsePornSlashStreamUrl(
            """
                <script>
                  fetch("https://other.example/master/not-player");
                  n.loadSource("https://deeper-bond-lexington-coalition.trycloudflare.com/master/HxkQQekoxeuQzwketotM");
                </script>
            """.trimIndent(),
        )

        assertEquals(
            "https://deeper-bond-lexington-coalition.trycloudflare.com/master/HxkQQekoxeuQzwketotM",
            stream,
        )
        assertNull(
            AdultWebsiteRepository.parsePornSlashStreamUrl(
                """<div data-preview="https://wolfcipher56-1343.pages.dev/13426/p/Preview.mp4"></div>""",
            ),
        )
        assertNull(
            AdultWebsiteRepository.normalizePornSlashStreamUrl(
                "http://deeper-bond-lexington-coalition.trycloudflare.com/master/HxkQQ",
            ),
        )
    }
    @Test
    fun providerPageNavigationRejectsUntrustedHostsAndInsecureSchemes() {
        assertTrue(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.Porn4Days,
                "https://www.porn4days.pw/video/sample",
            ),
        )
        assertTrue(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.PornHd3x,
                "https://pornhd3x.tv/video/sample",
            ),
        )
        assertTrue(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.PornHd3x,
                "https://www9.pornhd3x.tv/movies/sample",
            ),
        )
        assertTrue(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.PornHd3x,
                "https://embed.lulustream.com/e/sample",
            ),
        )
        assertTrue(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.PornHd3x,
                "https://doodstream.co/e/sample",
            ),
        )
        assertFalse(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.Porn4Days,
                "https://attacker.example/video/sample",
            ),
        )
        assertFalse(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.PornHd3x,
                "https://lulustream.com.attacker.example/e/sample",
            ),
        )
        assertFalse(
            AdultWebsiteRepository.isAllowedMainFrameUrl(
                AdultSource.PornHd3x,
                "http://doodstream.co/e/sample",
            ),
        )
        assertNull(
            AdultWebsiteRepository.normalizePageUrl(
                AdultSource.PornHd3x,
                "http://www.pornhd3x.tv/video/sample",
            ),
        )
        assertNull(
            AdultWebsiteRepository.normalizePageUrl(
                AdultSource.PornHd3x,
                "https://attacker.pornhd3x.tv/video/sample",
            ),
        )
    }

    @Test
    fun porn4DaysUsesOnlyItsPublishedHlsSource() {
        val source = AdultWebsiteRepository.parsePorn4DaysStreamUrl(
            """
                <script>
                  const SERVER1_URL = "https://video.amateur4days.com/path/signed.m3u8?token=abc";
                  const SERVER2_URL = "https://player.abyssplayer.com/embed/123";
                </script>
            """.trimIndent(),
        )

        assertEquals(
            "https://video.amateur4days.com/path/signed.m3u8?token=abc",
            source,
        )
        assertNull(
            AdultWebsiteRepository.parsePorn4DaysStreamUrl(
                """<script>const SERVER1_URL = "https://attacker.example/video.m3u8";</script>""",
            ),
        )
    }

    @Test
    fun pornHd3xAcceptsOnlyDirectPlayerSourcesNotPreviewVideos() {
        val source = AdultWebsiteRepository.parsePornHd3xStreamUrl(
            """
                <script>
                  jwplayer("video").setup({
                    sources: [
                      { file: "https://prog-public-ec.project1content.com/media/full.mp4?token=abc" }
                    ]
                  });
                </script>
            """.trimIndent(),
        )
        assertEquals(
            "https://prog-public-ec.project1content.com/media/full.mp4?token=abc",
            source,
        )

        assertNull(
            AdultWebsiteRepository.parsePornHd3xStreamUrl(
                """<div data-preview="https://prog-public-ec.project1content.com/teaser.mp4"></div>""",
            ),
        )
        assertNull(
            AdultWebsiteRepository.parsePornHd3xStreamUrl(
                """<script>jwplayer("video").setup({file:"https://attacker.example/full.mp4"});</script>""",
            ),
        )
    }
}
