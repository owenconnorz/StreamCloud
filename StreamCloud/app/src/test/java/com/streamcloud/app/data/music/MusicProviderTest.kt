package com.streamcloud.app.data.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicProviderTest {
    @Test fun unknownOrMissingProviderUsesYouTubeMusic() {
        assertEquals(MusicProvider.YouTubeMusic, MusicProvider.fromId(null))
        assertEquals(MusicProvider.YouTubeMusic, MusicProvider.fromId("uninstalled-provider"))
    }

    @Test fun providerIdsRoundTrip() {
        MusicProvider.entries.forEach {
            assertEquals(it, MusicProvider.fromId(it.id))
        }
        assertEquals(MusicProvider.entries.size, MusicProvider.entries.map { it.id }.distinct().size)
    }

    @Test fun githubRepositoryLinksNormalizeToRawCatalogueOnly() {
        assertEquals(
            "https://raw.githubusercontent.com/owner/repository/main/catalog.json",
            MusicProviderCatalogRepository.normalizeRepositoryUrl(
                "https://github.com/owner/repository/",
            ),
        )
        assertEquals(
            "https://raw.githubusercontent.com/owner/repository/main/catalog.json",
            MusicProviderCatalogRepository.normalizeRepositoryUrl(
                "https://raw.githubusercontent.com/owner/repository/main/catalog.json",
            ),
        )
    }

    @Test fun rejectsNonGitHubAndUnsafeRepositoryLinks() {
        listOf(
            "http://github.com/owner/repository",
            "https://example.com/owner/repository",
            "https://github.com/../repository",
            "https://github.com/owner/repository?token=secret",
            "https://github.com/owner/repository/tree/main",
        ).forEach { link ->
            try {
                MusicProviderCatalogRepository.normalizeRepositoryUrl(link)
                throw AssertionError("Expected unsafe or unsupported link to be rejected: $link")
            } catch (_: IllegalArgumentException) {
                // Expected: only canonical HTTPS GitHub links are accepted.
            }
        }
    }
}
