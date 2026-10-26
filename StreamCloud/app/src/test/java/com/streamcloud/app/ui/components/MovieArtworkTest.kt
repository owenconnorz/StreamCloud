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

    @Test
    fun `tv poster artwork requests a larger TMDB size`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/poster.jpg",
            tvHighResolutionTmdbArtworkUrl("https://image.tmdb.org/t/p/w500/poster.jpg"),
        )
    }

    @Test
    fun `tv backdrops request original TMDB artwork`() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/backdrop.jpg",
            tvHighResolutionTmdbArtworkUrl("https://image.tmdb.org/t/p/w1280/backdrop.jpg"),
        )
    }

    @Test
    fun `tv artwork resolution upgrade leaves other image hosts unchanged`() {
        assertEquals(
            "https://images.example.test/w500/poster.jpg",
            tvHighResolutionTmdbArtworkUrl("https://images.example.test/w500/poster.jpg"),
        )
    }
}