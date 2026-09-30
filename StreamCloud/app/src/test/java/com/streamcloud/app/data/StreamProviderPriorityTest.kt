package com.streamcloud.app.data

import org.junit.Assert.assertEquals
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
}