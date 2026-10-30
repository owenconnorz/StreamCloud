package com.streamcloud.app.torrent

import com.nuvio.engine.NuvioTorrentFile
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentFileSelectorTest {
    private fun file(index: Int, path: String, size: Long) = NuvioTorrentFile(
        index = index,
        offset = 0L,
        size = size,
        path = path,
        pathTruncated = false,
    )

    @Test
    fun requestedIndexIsZeroBasedAndHonored() {
        val files = listOf(file(0, "movie.mkv", 500), file(1, "extra.mp4", 100))

        assertEquals(1, TorrentFileSelector.select(files, requestedIndex = 1, filename = null))
    }

    @Test
    fun filenameTakesPriorityOverIndex() {
        val files = listOf(file(0, "movie.mkv", 500), file(1, "extras/feature.mp4", 100))

        assertEquals(1, TorrentFileSelector.select(files, requestedIndex = 0, filename = "feature.mp4"))
    }

    @Test
    fun fallbackChoosesLargestVideoInsteadOfNonVideoArchive() {
        val files = listOf(
            file(0, "small.mp4", 500),
            file(1, "large.mkv", 700),
            file(2, "archive.zip", 9_000),
        )

        assertEquals(1, TorrentFileSelector.select(files, requestedIndex = null, filename = null))
    }
}
