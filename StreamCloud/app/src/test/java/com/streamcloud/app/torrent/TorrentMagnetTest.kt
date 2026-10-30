package com.streamcloud.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMagnetTest {
    @Test
    fun parsesFileIndexAndPreservesV2Topic() {
        val parsed = TorrentMagnet.parse(
            "magnet:?xt=urn%3Abtmh%3A1220abcdef&_sc_fidx=3&dn=Sample",
        )

        assertEquals(3, parsed.fileIndexHint)
        assertTrue(parsed.uri.contains("xt=urn%3Abtmh%3A1220abcdef"))
        assertFalse(parsed.uri.contains("_sc_fidx"))
        assertTrue(parsed.uri.contains("&tr="))
    }

    @Test
    fun buildsMagnetWithUniqueTrackers() {
        val parsed = TorrentMagnet.fromInfoHash(
            "0123456789abcdef0123456789abcdef01234567",
            listOf("udp://tracker.opentrackr.org:1337/announce", "udp://custom.test/announce"),
        )

        assertTrue(parsed.uri.startsWith("magnet:?xt=urn:btih:"))
        assertEquals(1, Regex("tracker\\.opentrackr").findAll(parsed.uri).count())
        assertTrue(parsed.uri.contains("custom.test"))
    }
}
