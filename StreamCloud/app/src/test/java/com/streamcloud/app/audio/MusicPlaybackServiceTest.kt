package com.streamcloud.app.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpUtil
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class MusicPlaybackServiceTest {

    @Test
    fun media3BuildsOneBoundedRangeHeaderFromResolvedContentLength() {
        val length = resolvedStreamDataLength(
            position = 0L,
            requestedLength = C.LENGTH_UNSET.toLong(),
            contentLength = 1_000L,
            requiresByteRange = true,
        )

        assertEquals(1_000L, length)
        assertEquals("bytes=0-999", HttpUtil.buildRangeRequestHeader(0L, length))
    }

    @Test
    fun existingSeekLengthRemainsOwnedByMedia3() {
        val length = resolvedStreamDataLength(
            position = 250L,
            requestedLength = 100L,
            contentLength = 1_000L,
            requiresByteRange = true,
        )

        assertEquals(100L, length)
        assertEquals("bytes=250-349", HttpUtil.buildRangeRequestHeader(250L, length))
    }

    @Test
    fun unboundedStreamsRemainUnbounded() {
        assertEquals(
            C.LENGTH_UNSET.toLong(),
            resolvedStreamDataLength(
                position = 0L,
                requestedLength = C.LENGTH_UNSET.toLong(),
                contentLength = 1_000L,
                requiresByteRange = false,
            ),
        )
    }
}