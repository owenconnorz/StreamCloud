package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubRepositoryTest {

    @Test
    fun homeParserPreservesTwoTitledRowsInProviderOrder() {
        val sections = parsePornhubHomeSections(
            """
            <main>
              <section id="recent">
                <h2>Recently added</h2>
                <ul><li data-video-vkey="one"><a href="/view_video.php?viewkey=one"
                  title="Sample one"></a></li></ul>
              </section>
              <section id="popular">
                <h2>Popular now</h2>
                <ul><li data-video-vkey="two"><a href="/view_video.php?viewkey=two"
                  title="Sample two"></a></li></ul>
              </section>
            </main>
            """.trimIndent(),
        )

        assertEquals(listOf("Recently added", "Popular now"), sections.map { it.title })
        assertEquals(listOf("one"), sections[0].items.map { it.id })
        assertEquals(listOf("two"), sections[1].items.map { it.id })
    }

    @Test
    fun siblingHeadingsAndListsRemainSeparateRows() {
        val sections = parsePornhubHomeSections(
            """
            <main>
              <div class="sectionTitle"><h2>First collection</h2></div>
              <ul><li data-video-vkey="first"><a href="/view_video.php?viewkey=first"
                title="First sample"></a></li></ul>
              <div class="sectionTitle"><h2>Second collection</h2></div>
              <ul><li data-video-vkey="second"><a href="/view_video.php?viewkey=second"
                title="Second sample"></a></li></ul>
            </main>
            """.trimIndent(),
        )

        assertEquals(listOf("First collection", "Second collection"), sections.map { it.title })
        assertEquals(listOf("first"), sections[0].items.map { it.id })
        assertEquals(listOf("second"), sections[1].items.map { it.id })
    }

    @Test
    fun unsectionedCardsAreKeptAlongsideTitledRowsInDomOrder() {
        val sections = parsePornhubHomeSections(
            """
            <main>
              <ul><li data-video-vkey="plain"><a href="/view_video.php?viewkey=plain"
                title="Plain sample"></a></li></ul>
              <section>
                <h2>Named collection</h2>
                <li data-video-vkey="named"><a href="/view_video.php?viewkey=named"
                  title="Named sample"></a></li>
              </section>
            </main>
            """.trimIndent(),
        )

        assertEquals(listOf("Videos", "Named collection"), sections.map { it.title })
        assertEquals(listOf("plain"), sections[0].items.map { it.id })
        assertEquals(listOf("named"), sections[1].items.map { it.id })
    }

    @Test
    fun nestedSectionListsBelongOnlyToTheirNearestHeading() {
        val sections = parsePornhubHomeSections(
            """
            <section>
              <h2>Outer row</h2>
              <li data-video-vkey="outer"><a href="/view_video.php?viewkey=outer"
                title="Outer sample"></a></li>
              <section>
                <h3>Inner row</h3>
                <li data-video-vkey="inner"><a href="/view_video.php?viewkey=inner"
                  title="Inner sample"></a></li>
                <div data-video-vkey="inner"><a href="/view_video.php?viewkey=inner"
                  title="Duplicate inner sample"></a></div>
              </section>
            </section>
            """.trimIndent(),
        )

        assertEquals(listOf("Outer row", "Inner row"), sections.map { it.title })
        assertEquals(listOf("outer"), sections[0].items.map { it.id })
        assertEquals(listOf("inner"), sections[1].items.map { it.id })
    }

    @Test
    fun unsectionedCardsUseClearlyLabelledFallback() {
        val sections = parsePornhubHomeSections(
            """
            <ul>
              <li data-video-vkey="plain"><a href="/view_video.php?viewkey=plain"
                title="Plain sample"></a></li>
            </ul>
            """.trimIndent(),
        )

        assertEquals(1, sections.size)
        assertEquals("Videos", sections.single().title)
        assertEquals("videos", sections.single().id)
        assertEquals(listOf("plain"), sections.single().items.map { it.id })
    }

    @Test
    fun emptyAndChallengePagesDoNotInventHomeSections() {
        assertTrue(parsePornhubHomeSections("<main></main>").isEmpty())
        assertTrue(
            parsePornhubHomeSections(
                """<html><body><div id="captcha">Loading...</div></body></html>""",
            ).isEmpty(),
        )
    }

    @Test
    fun authenticatedRequestsKeepPornhubProvidedCookies() {
        val cookies = pornhubRequestCookieHeader("session=authenticated; locale=en")

        assertTrue(cookies.contains("session=authenticated"))
        assertTrue(cookies.contains("locale=en"))
        assertTrue(cookies.contains("platform=mobile"))
        assertTrue(!cookies.contains("accessAgeDisclaimerPH"))
    }

    @Test
    fun existingPornhubCookieValuesAreNotOverridden() {
        assertEquals(
            "session=authenticated; accessAgeDisclaimerPH=1; platform=desktop",
            pornhubRequestCookieHeader(
                "session=authenticated; accessAgeDisclaimerPH=1; platform=desktop",
            ),
        )
    }

    @Test
    fun arrayShapedVideoUrlsAreUnpackedWithoutCrashing() {
        val html = """
            <script>
              var player = {
                mediaDefinitions: [
                  {"format":"hls","quality":"720","videoUrl":[{"videoUrl":"https://example.com/master.m3u8"}]},
                  {"format":"mp4","quality":"480","videoUrl":"https://example.com/video.mp4"}
                ]
              };
            </script>
        """.trimIndent()

        val sources = parsePornhubMediaDefinitions(html)

        assertEquals(2, sources.size)
        assertEquals("https://example.com/master.m3u8", sources.first().url)
    }

    @Test
    fun directMediaDefinitionResponsesAreParsedAsPlayableSources() {
        val sources = parsePornhubMediaDefinitions(
            """
            [
              {"format":"mp4","quality":"720","videoUrl":"https://cdn.example.com/video.mp4?token=abc"}
            ]
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("https://cdn.example.com/video.mp4?token=abc", sources.single().url)
    }

    @Test
    fun providerMediaApiEndpointsAreNotPresentedAsPlayableVideoSources() {
        val sources = parsePornhubMediaDefinitions(
            """
            {
              "mediaDefinitions": [
                {
                  "format": "mp4",
                  "quality": "1080",
                  "videoUrl": "https://www.pornhub.com/video/get_media?s=signed-token"
                },
                {
                  "format": "hls",
                  "quality": "720",
                  "videoUrl": "https://cdn.example.com/master.m3u8?token=abc"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("https://cdn.example.com/master.m3u8?token=abc"), sources.map { it.url })
    }

    @Test
    fun protocolRelativeVideoUrlsAreNormalizedForPlayback() {
        val html = """
            <script>
              var player = {
                mediaDefinitions: [
                  {"format":"mp4","quality":"720","videoUrl":"//cdn.example.com/video.mp4"}
                ]
              };
            </script>
        """.trimIndent()

        val source = parsePornhubMediaDefinitions(html).single()

        assertEquals("https://cdn.example.com/video.mp4", source.url)
    }

    @Test
    fun categoryCardsKeepPornhubImagesAndCounts() {
        val html = """
            <a href="/categories/amateur" data-title="Amateur">
              <img data-src="//cdn.example.com/amateur.jpg" alt="Amateur">
              <span>556,382 Videos</span>
            </a>
        """.trimIndent()

        val category = parsePornhubCategories(html).single()

        assertEquals("Amateur", category.title)
        assertEquals("556,382 Videos", category.countLabel)
        assertEquals("https://cdn.example.com/amateur.jpg", category.thumbnail)
    }

    @Test
    fun videoCardsUseLazyLoadedPosterAttributesForThumbnails() {
        val html = """
            <ul>
              <li data-video-vkey="poster-key" data-poster="//cdn.example.com/poster.jpg">
                <a href="/view_video.php?viewkey=poster-key" title="Poster sample">
                  <img data-thumbnail="https://cdn.example.com/fallback.jpg" alt="Poster sample">
                </a>
              </li>
            </ul>
        """.trimIndent()

        val item = parsePornhubListing(html).single()

        assertEquals("https://cdn.example.com/poster.jpg", item.thumbnail)
        assertEquals(item.thumbnail, item.previewImage)
    }

    @Test
    fun progressivePornhubSourceCanBePreferredForNativePlayback() {
        val selected = choosePornhubSource(
            sources = listOf(
                PornhubStreamSource("https://cdn.example.com/1080.m3u8", "hls", 1080),
                PornhubStreamSource("https://cdn.example.com/720.mp4", "mp4", 720),
            ),
            preferProgressive = true,
        )

        assertEquals("https://cdn.example.com/720.mp4", selected?.url)
    }
}