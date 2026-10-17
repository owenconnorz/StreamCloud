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

// The APK workflow selects this class; inherit the shared interoperability regressions too.
class NuvioSyncContractTest : NuvioProgressInteropTest() {
    @Test
    fun officialMillisecondRowsNeverGuessSecondsFromSmallNumbers() {
        val timestamp = 1_700_000_000_000L
        // One second into a long episode, and a short clip/unknown-duration row.
        for ((position, duration) in listOf(1_000L to 2_400_000L, 10_000L to 30_000L, 10L to 0L)) {
            assertEquals(
                NuvioProgressTimes(position, duration, timestamp),
                normalizeNuvioProgressTimes(position, duration, timestamp),
            )
        }
    }

    @Test
    fun officialEpisodeStorageKeyIsSeparateFromVideoId() {
        val local = NuvioWatchProgressDeleteKey(123L, "tv", 2, 5)
        val target = nuvioProgressUploadTargets(local, emptyList()).single()
        assertEquals("tmdb:123_s2e5", target.remoteProgressKey)
        assertEquals("tmdb:123:2:5", target.remoteVideoId)
        assertEquals(
            NuvioEpisodeNumbers(2, 5),
            nuvioEpisodeNumbers(null, null, null, null, target.remoteProgressKey),
        )
    }

    @Test
    fun updatesExistingImdbAndLegacyEpisodeAliasesInsteadOfCreatingNewRows() {
        val local = NuvioWatchProgressDeleteKey(123L, "tv", 2, 5)
        val imdb = local.copy(
            remoteContentId = "tt1234567", remoteVideoId = "tt1234567:2:5",
            remoteProgressKey = "tt1234567_s2e5",
        )
        val legacy = local.copy(remoteProgressKey = "tmdb:123:2:5")
        assertEquals(listOf(imdb, legacy), nuvioProgressUploadTargets(local, listOf(imdb, legacy)))
    }

    @Test
    fun acknowledgedRemoteRowsAreNotReuploadedButLocalPlaybackChangesRemainPending() {
        val acknowledged = nuvioProgressFingerprint(24_000L, 2_400_000L, 100L)
        assertFalse(isPendingNuvioProgress(acknowledged, acknowledged))
        val updated = nuvioProgressFingerprint(2_016_000L, 2_400_000L, 200L)
        assertTrue(isPendingNuvioProgress(updated, acknowledged))
        // A failed push leaves the acknowledgement unchanged and is retried.
        assertTrue(isPendingNuvioProgress(updated, acknowledged))
        assertFalse(isPendingNuvioProgress(updated, updated))
        val duringRequest = nuvioProgressFingerprint(2_030_000L, 2_400_000L, 210L)
        assertTrue(isPendingNuvioProgress(duringRequest, updated))
    }

    @Test
    fun fresherEpisodeAliasWinsAtEqualTimestampsWithoutOverwritingNewerLocalPlayback() {
        assertTrue(shouldUseNuvioProgress(100L, 2_400_000L, 100L, 2_400_000L, 2_400_000L,
            existingPositionMs = 24_000L, incomingPositionMs = 2_016_000L))
        assertFalse(shouldUseNuvioProgress(200L, 2_400_000L, 100L, 2_400_000L, 2_400_000L,
            existingPositionMs = 24_000L, incomingPositionMs = 2_016_000L))
    }

    @Test
    fun readsEpisodeNumbersFromProgressIdentityWhenSeparateFieldsAreMissing() {
        assertEquals(
            NuvioEpisodeNumbers(2, 5),
            nuvioEpisodeNumbers(
                null,
                null,
                null,
                null,
                "tmdb:123:2:5",
                "tmdb:123",
            ),
        )
    }

    @Test
    fun explicitEpisodeNumbersTakePrecedenceOverEncodedKeys() {
        assertEquals(
            NuvioEpisodeNumbers(3, 7),
            nuvioEpisodeNumbers(3, 2, 7, 5, "tmdb:123:2:5"),
        )
    }

    @Test
    fun ignoresEmptyEpisodeFieldsAndUsesNumberAliases() {
        assertEquals(
            NuvioEpisodeNumbers(2, 5),
            nuvioEpisodeNumbers(0, 2, 0, 5, "tmdb:123"),
        )
    }

    @Test
    fun readsEpisodeNumbersFromSeriesTitleAndRejectsUnidentifiedSeriesRows() {
        assertEquals(
            NuvioEpisodeNumbers(3, 2),
            nuvioEpisodeNumbers(null, null, null, null, "The Rookie S03E02"),
        )
        assertTrue(hasValidNuvioEpisodeIdentity(true, 3, 2))
        assertFalse(hasValidNuvioEpisodeIdentity(true, 0, 0))
        assertTrue(hasValidNuvioEpisodeIdentity(false, 0, 0))
    }

    @Test
    fun recognizesAllNuvioSeriesTypeAliases() {
        assertTrue(isNuvioSeriesType("series"))
        assertTrue(isNuvioSeriesType("tv"))
        assertTrue(isNuvioSeriesType("episode"))
        assertFalse(isNuvioSeriesType("movie"))
    }

    @Test
    fun neverPushesSeriesProgressWithoutAnEpisodeIdentity() {
        assertTrue(shouldPushNuvioWatchProgress("tv", 3, 2))
        assertFalse(shouldPushNuvioWatchProgress("tv", 0, 0))
        assertFalse(shouldPushNuvioWatchProgress("series", 3, 0))
        assertTrue(shouldPushNuvioWatchProgress("movie", 0, 0))
        assertFalse(shouldPushNuvioWatchProgress("music", 0, 0))
    }

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
    fun deleteTombstoneMatchesTheSameEpisodeButNotItsNeighbor() {
        val key = NuvioWatchProgressDeleteKey(
            tmdbId = 123L,
            mediaType = "tv",
            seasonNumber = 2,
            episodeNumber = 5,
            remoteContentId = "imdb:tt1234567",
            remoteContentType = "series",
        )

        assertTrue(key.matchesLocalProgress(123L, "series", 2, 5))
        assertFalse(key.matchesLocalProgress(123L, "tv", 2, 6))
        assertTrue(
            key.matchesRemoteProgress(
                tmdbId = null,
                mediaType = "series",
                seasonNumber = 2,
                episodeNumber = 5,
                remoteContentId = "imdb:tt1234567",
            ),
        )
        assertFalse(
            key.matchesRemoteProgress(
                tmdbId = null,
                mediaType = "series",
                seasonNumber = 2,
                episodeNumber = 6,
                remoteContentId = "imdb:tt1234567",
            ),
        )
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
    fun normalizesPositionAndDurationWhenNuvioReturnsMixedUnits() {
        assertEquals(
            NuvioProgressTimes(
                positionMs = 120_000L,
                durationMs = 2_400_000L,
                updatedAtMs = 1_700_000_000_000L,
            ),
            normalizeNuvioProgressTimes(
                position = 120L,
                duration = 2_400_000L,
                lastWatched = 1_700_000_000L,
            ),
        )
        assertEquals(
            NuvioProgressTimes(
                positionMs = 120_000L,
                durationMs = 2_400_000L,
                updatedAtMs = 1_700_000_000_000L,
            ),
            normalizeNuvioProgressTimes(
                position = 120_000L,
                duration = 2_400L,
                lastWatched = 1_700_000_000L,
            ),
        )
        val sistersProgress = normalizeNuvioProgressTimes(
            position = 2_016L,
            duration = 2_400_000L,
            lastWatched = 1_700_000_000L,
        )
        assertEquals(2_016_000L, sistersProgress.positionMs)
        assertEquals(2_400_000L, sistersProgress.durationMs)
        assertEquals(
            84.0,
            sistersProgress.positionMs.toDouble() / sistersProgress.durationMs * 100.0,
            0.001,
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
    fun remoteDeletionRemovesOnlyRowsFromThePreviousSnapshot() {
        assertTrue(
            shouldRemoveMissingNuvioSnapshotRow(
                wasInPreviousSnapshot = true,
                rowUpdatedAt = 100L,
                previousSnapshotAt = 200L,
            ),
        )
        assertFalse(
            shouldRemoveMissingNuvioSnapshotRow(
                wasInPreviousSnapshot = false,
                rowUpdatedAt = 100L,
                previousSnapshotAt = 200L,
            ),
        )
    }

    @Test
    fun remoteDeletionPreservesLocalChangesMadeAfterTheSnapshot() {
        assertFalse(
            shouldRemoveMissingNuvioSnapshotRow(
                wasInPreviousSnapshot = true,
                rowUpdatedAt = 300L,
                previousSnapshotAt = 200L,
            ),
        )
        assertFalse(
            shouldRemoveMissingNuvioSnapshotRow(
                wasInPreviousSnapshot = true,
                rowUpdatedAt = 100L,
                previousSnapshotAt = 0L,
            ),
        )
    }

    @Test
    fun immediateSyncRequestsAreQueuedBehindActiveSync() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequestWorkPolicy(),
        )
    }

    @Test
    fun playbackProgressSyncIsRateLimitedWithoutDroppingTheFirstRequest() {
        assertTrue(
            shouldEnqueuePlaybackProgressSync(
                lastRequestAtMs = null,
                nowMs = 10_000L,
                minimumIntervalMs = 60_000L,
            ),
        )
        assertFalse(
            shouldEnqueuePlaybackProgressSync(
                lastRequestAtMs = 10_000L,
                nowMs = 69_999L,
                minimumIntervalMs = 60_000L,
            ),
        )
        assertTrue(
            shouldEnqueuePlaybackProgressSync(
                lastRequestAtMs = 10_000L,
                nowMs = 70_000L,
                minimumIntervalMs = 60_000L,
            ),
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
