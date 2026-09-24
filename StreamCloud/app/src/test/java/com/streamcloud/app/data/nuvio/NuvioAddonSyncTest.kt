package com.streamcloud.app.data.nuvio

import org.junit.Assert.assertEquals
import org.junit.Test

class NuvioAddonSyncTest {

    @Test
    fun keepsRemoteAddonsWhenLocalStoreIsEmpty() {
        val remote = listOf(
            NuvioAddonSyncEntry("https://one.example/manifest.json", "One", true, 0),
        )

        assertEquals(remote, mergeNuvioAddonEntries(remote, emptyList()))
    }

    @Test
    fun unionsRemoteAndLocalAddonsWithoutCanonicalDuplicates() {
        val merged = mergeNuvioAddonEntries(
            remote = listOf(
                NuvioAddonSyncEntry("https://one.example/manifest.json", "Remote One", true, 0),
            ),
            local = listOf(
                NuvioAddonSyncEntry("https://one.example/", "Local One", true, 0),
                NuvioAddonSyncEntry("https://two.example/manifest.json", "Two", true, 1),
            ),
        )

        assertEquals(
            listOf(
                NuvioAddonSyncEntry("https://one.example/manifest.json", "Remote One", true, 0),
                NuvioAddonSyncEntry("https://two.example/manifest.json", "Two", true, 1),
            ),
            merged,
        )
    }

    @Test
    fun preservesRemoteDisabledStateAndQueryWhenMerging() {
        val remote = NuvioAddonSyncEntry(
            "https://one.example/manifest.json?key=abc",
            "One",
            false,
            5,
        )

        assertEquals(
            listOf(remote.copy(sortOrder = 0)),
            mergeNuvioAddonEntries(listOf(remote), emptyList()),
        )
        assertEquals(
            canonicalNuvioAddonUrl("https://one.example/?key=abc"),
            canonicalNuvioAddonUrl(remote.url),
        )
    }
}