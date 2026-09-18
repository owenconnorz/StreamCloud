package com.streamcloud.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieDetailDownloadGuardTest {
    @Test
    fun `episode title keeps original show identity and adds episode`() {
        assertEquals("The Example Show S2E7", episodeDownloadTitle("The Example Show", 2, 7))
    }

    @Test
    fun `completed different episode is blocked instead of replaced`() {
        val message = tvDownloadConflictMessage(
            existingTitle = "The Example Show S1E1",
            existingStatus = "done",
            hasActiveDownload = false,
            requestedTitle = "The Example Show S1E2",
        )

        assertTrue(message.orEmpty().contains("only one episode per show"))
        assertTrue(message.orEmpty().contains("not replaced"))
    }

    @Test
    fun `active download is blocked even before database status updates`() {
        val message = tvDownloadConflictMessage(
            existingTitle = null,
            existingStatus = null,
            hasActiveDownload = true,
            requestedTitle = "The Example Show S1E2",
        )

        assertTrue(message.orEmpty().contains("already active"))
    }

    @Test
    fun `failed legacy entry can be retried`() {
        assertNull(
            tvDownloadConflictMessage(
                existingTitle = "The Example Show S1E1",
                existingStatus = "error",
                hasActiveDownload = false,
                requestedTitle = "The Example Show S1E1",
            ),
        )
    }
}