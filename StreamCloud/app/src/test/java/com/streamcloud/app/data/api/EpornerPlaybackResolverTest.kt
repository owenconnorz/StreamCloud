package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EpornerPlaybackResolverTest {

    @Test
    fun parsesVideoConfigurationFromEmbeddedPlayerPage() {
        val config = parseEpornerResolverEmbedConfig(
            """
            <script>
              EP.video.player.vid = 'video-id-123';
              EP.video.player.hash = '00000000000000000000000000000000';
            </script>
            """.trimIndent(),
        )

        assertNotNull(config)
        assertEquals("video-id-123", config?.videoId)
        assertEquals("0000", config?.encodedHash)
    }

    @Test
    fun prefersAdaptiveHlsThenKeepsMp4AsFallback() {
        val response = EpornerResolverResponse(
            sources = EpornerResolverSources(
                hls = EpornerResolverAdaptiveSource(
                    auto = EpornerResolverSource(
                        src = "https://media.example/video.m3u8",
                    ),
                ),
                mp4 = mapOf(
                    "480p" to EpornerResolverSource(
                        src = "https://media.example/video-480.mp4",
                        height = 480,
                    ),
                ),
            ),
        )

        assertEquals("https://media.example/video.m3u8", response.bestEpornerResolverSource()?.src)
    }
}