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
    fun deleteKeyPreservesOriginalNuvioRecordIdentity() {
        val key = NuvioWatchProgressDeleteKey(
            tmdbId = 123L,
            mediaType = "tv",
            seasonNumber = 2,
            episodeNumber = 5,
            remoteContentId = "imdb:tt1234567",
            remoteContentType = "series",
            remoteVideoId = "tt1234567:2:5",
            remoteProgressKey = "imdb:tt1234567:2:5",
        )

        val restored = NuvioWatchProgressDeleteKey.deserialize(key.serialize())

        assertEquals("imdb:tt1234567", restored?.contentId)
        assertEquals("series", restored?.contentType)
        assertEquals("tt1234567:2:5", restored?.remoteVideoId)
        assertEquals("imdb:tt1234567:2:5", restored?.remoteProgressKey)
        assertEquals(123L, restored?.tmdbId)
        assertEquals(2, restored?.seasonNumber)
        assertEquals(5, restored?.episodeNumber)
    }

    @Test
    fun readsLegacyProgressDeleteTombstones() {
        val restored = NuvioWatchProgressDeleteKey.deserialize("123|tv|2|5")

        assertEquals("tmdb:123", restored?.contentId)
        assertEquals("tmdb:123:2:5", restored?.remoteKey)
    }

    @Test
    fun normalizesSecondBasedNuvioProgressToMilliseconds() {
        assertEquals(
            NuvioProgressTimes(
                positionMs = 300_000L,
                durationMs = 3_600_000L,
                updatedAtMs = 1_700_000_000_000L,
            ),
            normalizeNuvioProgressTimes(
                position = 300L,
                duration = 3_600L,
                lastWatched = 1_700_000_000L,
            ),
        )
    }

    @Test
    fun leavesMillisecondNuvioProgressUnchanged() {
        assertEquals(
            NuvioProgressTimes(
                positionMs = 300_000L,
                durationMs = 3_600_000L,
                updatedAtMs = 1_700_000_000_000L,
            ),
            normalizeNuvioProgressTimes(
                position = 300_000L,
                duration = 3_600_000L,
                lastWatched = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun correctsPreviouslyImportedSecondBasedDurationEvenWhenTimestampMatches() {
        assertTrue(
            shouldUseNuvioProgress(
                existingUpdatedAt = 1_700_000_000_000L,
                existingDurationMs = 3_600L,
                incomingUpdatedAtMs = 1_700_000_000_000L,
                incomingRawDuration = 3_600L,
                incomingDurationMs = 3_600_000L,
            ),
        )
    }

    @Test
    fun doesNotReplaceNewerLocalPlaybackWithOlderNuvioProgress() {
        assertFalse(
            shouldUseNuvioProgress(
                existingUpdatedAt = 1_700_000_100_000L,
                existingDurationMs = 3_600_000L,
                incomingUpdatedAtMs = 1_700_000_000_000L,
                incomingRawDuration = 3_600L,
                incomingDurationMs = 3_600_000L,
            ),
        )
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
