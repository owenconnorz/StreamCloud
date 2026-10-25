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
}
