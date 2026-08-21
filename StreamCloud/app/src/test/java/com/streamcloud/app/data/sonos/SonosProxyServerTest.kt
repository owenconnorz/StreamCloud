package com.streamcloud.app.data.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonosProxyServerTest {

    @Test
    fun readsTheCompleteLengthFromARangedUpstreamResponse() {
        assertEquals(
            9_876_543L,
            upstreamContentLength(
                contentRange = "bytes 0-1/9876543",
                contentLength = "2",
            ),
        )
    }

    @Test
    fun usesContentLengthForAnUnrangedResponse() {
        assertEquals(
            321L,
            upstreamContentLength(contentRange = null, contentLength = "321"),
        )
    }

    @Test
    fun rejectsUnknownOrInvalidLengths() {
        assertNull(upstreamContentLength(contentRange = "bytes */*", contentLength = "0"))
    }

    @Test
    fun doesNotUseThePartialContentLengthAsTheSongLength() {
        assertEquals(
            9_876_543L,
            fullLengthFromProbe(
                statusCode = 206,
                contentRange = null,
                contentLength = "2",
                declaredLength = 9_876_543L,
            ),
        )
    }

    @Test
    fun advertisesRangesOnlyWhenTheSourceHonorsTheProbe() {
        assertEquals(true, supportsByteRanges(206, "bytes 0-1/100"))
        assertEquals(false, supportsByteRanges(200, null))
    }
}