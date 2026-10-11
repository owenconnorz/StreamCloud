package com.streamcloud.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun parsesEnhancedLrcWordTimestamps() {
        val lines = LyricsRepository.parseTimedLrc(
            "[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:03.00]Next",
        )

        assertEquals(2, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertTrue(lines[0].hasWordTimestamps)
        assertEquals(0, LyricsRepository.activeWordIndex(lines[0], lines[1].timestampMs, 1_400))
        assertEquals(1, LyricsRepository.activeWordIndex(lines[0], lines[1].timestampMs, 1_600))
    }

    @Test
    fun estimatesWordProgressForOrdinaryLineSyncedLrc() {
        val lines = LyricsRepository.parseTimedLrc(
            "[00:01.00]One two three\n[00:04.00]Next line",
        )

        assertFalse(lines[0].hasWordTimestamps)
        assertEquals(0, LyricsRepository.activeWordIndex(lines[0], lines[1].timestampMs, 1_000))
        assertEquals(1, LyricsRepository.activeWordIndex(lines[0], lines[1].timestampMs, 2_000))
        assertEquals(2, LyricsRepository.activeWordIndex(lines[0], lines[1].timestampMs, 3_000))
    }

    @Test
    fun doesNotHighlightEnhancedWordBeforeItsTimestamp() {
        val line = LyricsRepository.parseTimedLrc("[00:01.00]<00:01.50>Wait")[0]

        assertEquals(-1, LyricsRepository.activeWordIndex(line, null, 1_200))
        assertEquals(0, LyricsRepository.activeWordIndex(line, null, 1_500))
    }
}
