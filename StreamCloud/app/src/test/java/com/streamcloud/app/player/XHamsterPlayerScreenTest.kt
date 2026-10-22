package com.streamcloud.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XHamsterPlayerScreenTest {
    @Test
    fun acceptsSupportedSecureProviderStreamsWithSignedQueryParameters() {
        assertTrue(
            isNativePlayableXHamsterStream(
                "https://cdn.example/video/stream.m3u8?token=temporary",
            ),
        )
        assertTrue(
            isNativePlayableXHamsterStream(
                "https://cdn.example/video/clip.MP4?quality=720",
            ),
        )
    }

    @Test
    fun rejectsInsecureBlobAndUnsupportedMediaUrls() {
        assertFalse(isNativePlayableXHamsterStream("http://cdn.example/stream.m3u8"))
        assertFalse(isNativePlayableXHamsterStream("blob:https://xhamster.com/video-id"))
        assertFalse(isNativePlayableXHamsterStream("https://cdn.example/player"))
        assertFalse(isNativePlayableXHamsterStream("https:///video.mp4"))
    }
}
