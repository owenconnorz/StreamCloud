package com.streamcloud.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieArtworkTest {
    @Test
    fun `blank primary uses fallback`() {
        assertEquals(
            listOf("https://example.test/backdrop.jpg"),
            movieArtworkUrls("  ", " https://example.test/backdrop.jpg "),
        )
    }

    @Test
    fun `primary and fallback retain retry order`() {
        assertEquals(
            listOf(
                "https://example.test/poster.jpg",
                "https://example.test/backdrop.jpg",
            ),
            movieArtworkUrls(
                "https://example.test/poster.jpg",
                "https://example.test/backdrop.jpg",
            ),
        )
    }

    @Test
    fun `duplicate fallback is not retried`() {
        assertEquals(
            listOf("https://example.test/poster.jpg"),
            movieArtworkUrls(
                "https://example.test/poster.jpg",
                "https://example.test/poster.jpg",
            ),
        )
    }

    @Test
    fun `missing urls produce no request`() {
        assertEquals(emptyList<String>(), movieArtworkUrls(null, "\t"))
    }
}