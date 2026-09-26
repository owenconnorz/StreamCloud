package com.streamcloud.app.data.nuvio

import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.normalizeStremioManifestUrl
import com.streamcloud.app.data.stremio.reorderInstalledStremioAddons
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioAddonSyncTest {

    @Test
    fun onlyTreatsVerifiedCompleteRestRangesAsDeletionEvidence() {
        assertTrue(isCompleteNuvioAddonSnapshot("0-1/2", 2))
        assertTrue(isCompleteNuvioAddonSnapshot("*/0", 0))
        assertFalse(isCompleteNuvioAddonSnapshot("0-1/3", 2))
        assertFalse(isCompleteNuvioAddonSnapshot("0-1/*", 2))
        assertFalse(isCompleteNuvioAddonSnapshot(null, 0))
    }

    @Test
    fun failedAddonWarningKeepsHttpStatusButDoesNotExposeUrlCredentials() {
        val warning = describeNuvioAddonManifestFailure(
            "https://addons.example/manifest.json?user=private-user-key",
            IOException(
                "HTTP 404 from https://addons.example/manifest.json?user=private-user-key",
            ),
        )

        assertEquals("addon from addons.example returned HTTP 404", warning)
        assertFalse(warning.contains("private-user-key"))
    }

    @Test
    fun failedAddonWarningDescribesNetworkFailureWithoutUrlQuery() {
        val warning = describeNuvioAddonManifestFailure(
            "https://addons.example/manifest.json?token=secret",
            IOException("Timed out while fetching addon manifest"),
        )

        assertEquals("addon from addons.example could not be installed", warning)
        assertFalse(warning.contains("secret"))
    }

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
                NuvioAddonSyncEntry("https://ONE.example/", "Local One", true, 0),
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
    fun explicitDeletionExcludesOnlyTheRequestedAddonFromTheMergedSnapshot() {
        val merged = mergeNuvioAddonEntries(
            remote = listOf(
                NuvioAddonSyncEntry("https://remove.example/manifest.json", "Remove", true, 0),
                NuvioAddonSyncEntry("https://keep.example/manifest.json", "Keep", true, 1),
            ),
            local = listOf(
                NuvioAddonSyncEntry("https://remove.example/", "Remove", true, 0),
                NuvioAddonSyncEntry("https://local.example/manifest.json", "Local", true, 1),
            ),
            excludedUrls = setOf("https://REMOVE.example/"),
        )

        assertEquals(
            listOf(
                NuvioAddonSyncEntry("https://keep.example/manifest.json", "Keep", true, 0),
                NuvioAddonSyncEntry("https://local.example/manifest.json", "Local", true, 1),
            ),
            merged,
        )
    }

    @Test
    fun detectsRemoteDeletionOnlyFromCompleteSnapshotAndPreservesRecentLocalReinstall() {
        val previous = setOf(
            "https://removed.example/manifest.json",
            "https://still-there.example/manifest.json",
        )
        val remote = listOf("https://still-there.example/")

        assertEquals(
            setOf("https://removed.example"),
            removedNuvioAddonKeys(
                remoteUrls = remote,
                previouslySyncedUrls = previous,
                locallyAddedAfterSnapshot = emptySet(),
                snapshotIsComplete = true,
            ),
        )
        assertEquals(
            emptySet<String>(),
            removedNuvioAddonKeys(
                remoteUrls = emptyList(),
                previouslySyncedUrls = previous,
                locallyAddedAfterSnapshot = emptySet(),
                snapshotIsComplete = false,
            ),
        )
        assertEquals(
            emptySet<String>(),
            removedNuvioAddonKeys(
                remoteUrls = remote,
                previouslySyncedUrls = previous,
                locallyAddedAfterSnapshot = setOf("https://removed.example/"),
                snapshotIsComplete = true,
            ),
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
    fun preservesPreviouslySyncedAddonsOmittedFromNonEmptyRemoteSnapshot() {
        assertEquals(
            listOf(
                "https://one.example",
                "https://local-only.example",
                "https://two.example",
            ),
            reconcileNuvioAddonUrls(
                remote = listOf("https://one.example/manifest.json"),
                local = listOf("https://local-only.example/manifest.json"),
                lastRemote = setOf("https://one.example", "https://two.example"),
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
            local.map(::canonicalNuvioAddonUrl),
            reconcileNuvioAddonUrls(
                remote = emptyList(),
                local = emptyList(),
                lastRemote = local.map(::canonicalNuvioAddonUrl).toSet(),
            ),
        )
    }

    @Test
    fun snapshotKeepsCaseSensitiveUrlValues() {
        assertEquals(
            setOf(
                "https://addon.example?user=AbC123/",
                "https://addon.example?user=abc123",
            ),
            canonicalNuvioAddonSnapshotUrls(
                listOf(
                    "https://addon.example/manifest.json?user=AbC123/",
                    "https://addon.example/manifest.json?user=abc123",
                ),
            ),
        )
    }

    @Test
    fun restoredAddonUrlKeepsAuthQueryAfterManifestPath() {
        assertEquals(
            "https://addon.example/manifest.json?user=AbC123/",
            normalizeStremioManifestUrl("https://addon.example?user=AbC123/"),
        )
        assertEquals(
            "https://addon.example/manifest.json?user=AbC123/",
            normalizeStremioManifestUrl(
                "https://addon.example/manifest.json?user=AbC123/",
            ),
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
    fun blocksOnlyAnEmptyReplacementWhenProfilePreviouslyHadAddons() {
        assertTrue(
            shouldBlockEmptyNuvioAddonReplacement(
                replacement = emptyList(),
                lastRemote = setOf("https://one.example"),
            ),
        )
        assertFalse(
            shouldBlockEmptyNuvioAddonReplacement(
                replacement = listOf("https://one.example/manifest.json"),
                lastRemote = setOf("https://one.example"),
            ),
        )
        assertFalse(
            shouldBlockEmptyNuvioAddonReplacement(
                replacement = emptyList(),
                lastRemote = emptySet(),
            ),
        )
    }
}