package com.streamcloud.app.data.nuvio

import androidx.work.ExistingWorkPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
