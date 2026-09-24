package com.streamcloud.app.data.nuvio

import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.reorderInstalledStremioAddons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun nonEmptyRemoteSnapshotFollowsRemoteOrderAndKeepsUntrackedLocalAddons() {
        assertEquals(
            listOf(
                "https://two.example",
                "https://one.example",
                "https://local-only.example",
            ),
            reconcileNuvioAddonUrls(
                remote = listOf(
                    "https://two.example/manifest.json",
                    "https://one.example/manifest.json",
                ),
                local = listOf(
                    "https://local-only.example/manifest.json",
                    "https://one.example/",
                ),
                lastRemote = setOf("https://one.example"),
            ),
        )
    }

    @Test
    fun removesOnlyLocallyKnownEntriesMissingFromNonEmptyRemoteSnapshot() {
        assertEquals(
            listOf("https://removed.example/manifest.json"),
            staleNuvioAddonUrls(
                remote = listOf("https://one.example/manifest.json"),
                local = listOf(
                    "https://one.example/",
                    "https://removed.example/manifest.json",
                    "https://other-profile.example/manifest.json",
                ),
                lastRemote = setOf(
                    "https://one.example",
                    "https://removed.example",
                    "https://other-profile.example",
                ),
                protectedRemote = setOf("https://other-profile.example"),
            ),
        )
    }

    @Test
    fun emptyRemoteSnapshotPreservesExistingLocalAddons() {
        val local = listOf(
            "https://one.example/manifest.json",
            "https://two.example/manifest.json?key=abc",
        )

        assertEquals(emptyList<String>(), reconcileNuvioAddonUrls(emptyList(), emptyList()))
        assertEquals(
            local.map(::canonicalNuvioAddonUrl),
            reconcileNuvioAddonUrls(
                remote = emptyList(),
                local = local,
                lastRemote = local.map(::canonicalNuvioAddonUrl).toSet(),
            ),
        )
        assertEquals(
            emptyList<String>(),
            staleNuvioAddonUrls(emptyList(), local, local.toSet()),
        )
    }

    @Test
    fun reconciliationDeduplicatesCanonicalUrlsAndPreservesLocalUrlForm() {
        assertEquals(
            listOf("https://one.example"),
            reconcileNuvioAddonUrls(
                remote = listOf(
                    "https://one.example/manifest.json",
                    "https://ONE.example/",
                ),
                local = listOf("https://one.example/"),
            ),
        )
    }

    @Test
    fun reordersInstalledAddonsByRemoteOrderAndKeepsUnlistedAddonsStable() {
        val one = InstalledStremioAddon(
            id = "one",
            name = "One",
            manifestUrl = "https://one.example/manifest.json?key=abc",
            baseUrl = "https://one.example",
            installedAt = 1,
        )
        val localOnly = InstalledStremioAddon(
            id = "local",
            name = "Local",
            manifestUrl = "https://local-only.example/manifest.json",
            baseUrl = "https://local-only.example",
            installedAt = 2,
        )
        val two = InstalledStremioAddon(
            id = "two",
            name = "Two",
            manifestUrl = "https://two.example/manifest.json",
            baseUrl = "https://two.example",
            installedAt = 3,
        )

        assertEquals(
            listOf(two, one, localOnly),
            reorderInstalledStremioAddons(
                installed = listOf(one, localOnly, two),
                orderedUrls = listOf("https://two.example", "https://one.example?key=abc"),
            ),
        )
    }

    @Test
    fun blocksEmptyReplacementWhenProfilePreviouslyHadAddons() {
        assertTrue(
            shouldBlockEmptyNuvioAddonReplacement(
                remote = emptyList(),
                lastRemote = setOf("https://one.example"),
            ),
        )
        assertFalse(
            shouldBlockEmptyNuvioAddonReplacement(
                remote = listOf("https://one.example/manifest.json"),
                lastRemote = setOf("https://one.example"),
            ),
        )
        assertFalse(
            shouldBlockEmptyNuvioAddonReplacement(
                remote = emptyList(),
                lastRemote = emptySet(),
            ),
        )
    }
}