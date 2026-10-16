package com.streamcloud.app.data.nuvio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NuvioRuntimeSubtitleParsingTest {
    @Test
    fun parsesSubtitleUrlsLanguageLabelsAndHeadersFromNuvioStreams() {
        val stream = NuvioRuntime.parseStreams(
            """
            [{
              "url": "https://video.example/movie.m3u8",
              "name": "Main stream",
              "subtitles": [{
                "url": "https://subs.example/movie.en.vtt",
                "language": "en",
                "name": "English",
                "headers": { "X-Subtitle-Token": "test-value" }
              }]
            }]
            """.trimIndent(),
        ).single()

        val subtitle = stream.subtitles?.single()
        assertNotNull(subtitle)
        assertEquals("https://subs.example/movie.en.vtt", subtitle?.url)
        assertEquals("en", subtitle?.language)
        assertEquals("English", subtitle?.name)
        assertEquals("test-value", subtitle?.headers?.get("X-Subtitle-Token"))
    }
}
