package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubRepositoryTest {

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
}