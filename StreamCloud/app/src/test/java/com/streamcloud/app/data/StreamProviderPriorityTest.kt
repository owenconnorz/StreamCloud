package com.streamcloud.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProviderPriorityTest {
    @Test
    fun savedPriorityOverridesDefaultInstallOrderAndLeavesNewProvidersLast() {
        val entries = listOf(
            StreamProviderPriorityEntry("stremio:catalog", "Catalog", "Stremio add-on"),
            StreamProviderPriorityEntry("nuvio:play", "Play", "Nuvio provider"),
            StreamProviderPriorityEntry("cs:lime", "Lime", "CloudStream plugin"),
            StreamProviderPriorityEntry("cs:new", "New", "CloudStream plugin"),
        )

        val ordered = orderStreamProviderEntries(
            entries,
            savedOrder = listOf("cs:lime", "nuvio:play", "stale:provider"),
        )

        assertEquals(
            listOf("cs:lime", "nuvio:play", "stremio:catalog", "cs:new"),
            ordered.map { it.key },
        )
    }

    @Test
    fun emptySavedPriorityKeepsInstalledOrder() {
        val entries = listOf(
            StreamProviderPriorityEntry("stremio:catalog", "Catalog", "Stremio add-on"),
            StreamProviderPriorityEntry("cs:lime", "Lime", "CloudStream plugin"),
        )

        assertEquals(entries, orderStreamProviderEntries(entries, emptyList()))
    }

    @Test
    fun autoPlayAppliesToSourceRoutesButDownloadsStayManual() {
        assertTrue(shouldAutoPlayBestStream(autoPlayEnabled = true, isDownloadRequest = false))
        assertFalse(shouldAutoPlayBestStream(autoPlayEnabled = true, isDownloadRequest = true))
        assertFalse(shouldAutoPlayBestStream(autoPlayEnabled = false, isDownloadRequest = false))
    }

    @Test
    fun autoPlayShowsResolvingStateUntilPlaybackCanStartOrNoSourcesRemain() {
        assertTrue(
            shouldShowAutoPlayResolvingState(
                autoPlayEnabled = true,
                isLoading = true,
                hasSources = false,
            ),
        )
        assertTrue(
            shouldShowAutoPlayResolvingState(
                autoPlayEnabled = true,
                isLoading = false,
                hasSources = true,
            ),
        )
        assertFalse(
            shouldShowAutoPlayResolvingState(
                autoPlayEnabled = true,
                isLoading = false,
                hasSources = false,
            ),
        )
        assertFalse(
            shouldShowAutoPlayResolvingState(
                autoPlayEnabled = false,
                isLoading = true,
                hasSources = false,
            ),
        )
    }
}