package com.streamcloud.app.data.nuvio

import androidx.work.ExistingWorkPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioSyncContractTest {
    @Test
    fun extractsStreamCloudSettingsFromRpcRow() {
        val settings = extractStreamCloudHomeSettings(
            """[{"platform":"streamcloud","settings_json":{"streamcloud":{"schema_version":1,"show_hero_section":false}}}]""",
        )

        assertEquals(
            Json.parseToJsonElement("""{"schema_version":1,"show_hero_section":false}""").jsonObject,
            settings,
        )
    }

    @Test
    fun extractsStreamCloudSettingsWhenJsonbIsEncodedAsString() {
        val settings = extractStreamCloudHomeSettings(
            """{"settings_json":"{\"streamcloud\":{\"schema_version\":1}}"}""",
        )

        assertEquals("1", settings?.get("schema_version")?.jsonPrimitive?.content)
    }

    @Test
    fun ignoresSettingsWithoutStreamCloudNamespace() {
        assertNull(
            extractStreamCloudHomeSettings(
                """{"settings_json":{"nuvio":{"show_hero_section":false}}}""",
            ),
        )
    }

    @Test
    fun episodeProgressDeletionUsesEpisodeIdentity() {
        val key = NuvioWatchProgressDeleteKey(
            tmdbId = 123L,
            mediaType = "tv",
            seasonNumber = 2,
            episodeNumber = 5,
        )

        assertEquals("series", key.contentType)
        assertEquals("tmdb:123", key.contentId)
        assertEquals("tmdb:123:2:5", key.remoteKey)
    }

    @Test
    fun playbackProgressSyncCoalescesButExplicitSyncIsAppended() {
        assertEquals(ExistingWorkPolicy.KEEP, syncRequestWorkPolicy(progressOnly = true))
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequestWorkPolicy(progressOnly = false),
        )
    }

    private fun homeSnapshot(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun cleanFirstSyncAcceptsCloudSettings() {
        assertTrue(
            HomeCatalogSyncPolicy.shouldApplyRemoteSnapshot(
                localSnapshot = homeSnapshot("""{"show_hero_section":true,"stremio_disabled_catalogs_csv":null}"""),
                lastAcknowledgedSnapshot = null,
                hasPendingLocalChanges = false,
                hasLegacyExplicitLocalValues = false,
            ),
        )
    }

    @Test
    fun firstSyncPreservesExplicitLocalSettingsFromOlderVersions() {
        assertFalse(
            HomeCatalogSyncPolicy.shouldApplyRemoteSnapshot(
                localSnapshot = homeSnapshot("""{"show_hero_section":true,"stremio_disabled_catalogs_csv":"addon::catalog"}"""),
                lastAcknowledgedSnapshot = null,
                hasPendingLocalChanges = false,
                hasLegacyExplicitLocalValues = true,
            ),
        )
    }

    @Test
    fun pendingLocalChangesWinOverTheCloudSnapshot() {
        val local = homeSnapshot("""{"show_hero_section":true}""")
        assertFalse(
            HomeCatalogSyncPolicy.shouldApplyRemoteSnapshot(
                localSnapshot = local,
                lastAcknowledgedSnapshot = HomeCatalogSyncPolicy.canonicalSnapshot(local),
                hasPendingLocalChanges = true,
                hasLegacyExplicitLocalValues = false,
            ),
        )
    }

    @Test
    fun changesSinceLastAcknowledgementAreNotReplaced() {
        assertFalse(
            HomeCatalogSyncPolicy.shouldApplyRemoteSnapshot(
                localSnapshot = homeSnapshot("""{"show_hero_section":false}"""),
                lastAcknowledgedSnapshot = HomeCatalogSyncPolicy.canonicalSnapshot(
                    homeSnapshot("""{"show_hero_section":true}"""),
                ),
                hasPendingLocalChanges = false,
                hasLegacyExplicitLocalValues = false,
            ),
        )
    }

    @Test
    fun unchangedLocalSnapshotAcceptsNewCloudSettings() {
        val local = homeSnapshot("""{"show_hero_section":true}""")
        assertTrue(
            HomeCatalogSyncPolicy.shouldApplyRemoteSnapshot(
                localSnapshot = local,
                lastAcknowledgedSnapshot = HomeCatalogSyncPolicy.canonicalSnapshot(local),
                hasPendingLocalChanges = false,
                hasLegacyExplicitLocalValues = false,
            ),
        )
    }

    @Test
    fun canonicalSnapshotIgnoresObjectKeyOrder() {
        assertEquals(
            HomeCatalogSyncPolicy.canonicalSnapshot(homeSnapshot("""{"b":[2,1],"a":true}""")),
            HomeCatalogSyncPolicy.canonicalSnapshot(homeSnapshot("""{"a":true,"b":[2,1]}""")),
        )
    }

    @Test
    fun acknowledgementDoesNotClearEditsMadeWhilePushWasInFlight() {
        assertFalse(
            HomeCatalogSyncPolicy.isSentSnapshotStillCurrent(
                currentLocalSnapshot = homeSnapshot("""{"show_hero_section":false}"""),
                sentSnapshot = homeSnapshot("""{"show_hero_section":true}"""),
            ),
        )
    }

    @Test
    fun acknowledgementAcceptsAnUnchangedSnapshot() {
        assertTrue(
            HomeCatalogSyncPolicy.isSentSnapshotStillCurrent(
                currentLocalSnapshot = homeSnapshot("""{"show_hero_section":true}"""),
                sentSnapshot = homeSnapshot("""{"show_hero_section":true}"""),
            ),
        )
    }
}
