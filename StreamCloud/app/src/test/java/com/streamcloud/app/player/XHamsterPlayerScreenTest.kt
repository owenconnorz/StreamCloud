package com.streamcloud.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertTrue(isNativePlayableXHamsterStream("https://cdn.example/video/clip.m4v"))
        assertTrue(isNativePlayableXHamsterStream("https://cdn.example/video/clip.webm"))
        assertTrue(isNativePlayableXHamsterStream("https://cdn.example/manifest.mpd"))
    }

    @Test
    fun rejectsInsecureBlobAndUnsupportedMediaUrls() {
        assertFalse(isNativePlayableXHamsterStream("http://cdn.example/stream.m3u8"))
        assertFalse(isNativePlayableXHamsterStream("blob:https://xhamster.com/video-id"))
        assertFalse(isNativePlayableXHamsterStream("https://cdn.example/player"))
        assertFalse(isNativePlayableXHamsterStream("https:///video.mp4"))
    }

    @Test
    fun onlyDirectSecureVideoFilesAreDownloadable() {
        assertEquals(
            "mp4",
            directDownloadExtension("https://cdn.example/video.mp4?token=temporary"),
        )
        assertEquals(
            "webm",
            directDownloadExtension("https://cdn.example/video.webm"),
        )
        assertNull(directDownloadExtension("https://cdn.example/video.m3u8"))
        assertNull(directDownloadExtension("blob:https://xhamster.com/video-id"))
        assertNull(directDownloadExtension("http://cdn.example/video.mp4"))
    }
}
