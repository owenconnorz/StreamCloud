package com.streamcloud.app.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQualitySelectorTest {
    private val formats = Json.parseToJsonElement("""[
        {"itag":251,"bitrate":160000,"mimeType":"audio/webm; codecs=opus","audioQuality":"AUDIO_QUALITY_LOW"},
        {"itag":250,"bitrate":70000,"mimeType":"audio/webm; codecs=opus","audioQuality":"AUDIO_QUALITY_LOW"},
        {"itag":140,"bitrate":128000,"mimeType":"audio/mp4; codecs=mp4a.40.2","audioQuality":"AUDIO_QUALITY_MEDIUM"},
        {"itag":141,"bitrate":256000,"mimeType":"audio/mp4; codecs=mp4a.40.2","audioQuality":"AUDIO_QUALITY_HIGH"},
        {"itag":774,"bitrate":192000,"mimeType":"audio/webm; codecs=opus","audioQuality":"AUDIO_QUALITY_HIGH"},
        {"itag":999,"bitrate":320000,"mimeType":"audio/webm; codecs=opus"}
    ]""").jsonArray.map { it.jsonObject }

    @Test fun lowChoosesLowestAvailableBitrate() {
        assertEquals("250", AudioQualitySelector.select(formats, "low")?.get("itag")?.toString())
    }

    @Test fun mediumChoosesBestMediumFormat() {
        assertEquals("140", AudioQualitySelector.select(formats, "medium")?.get("itag")?.toString())
    }

    @Test fun highPrefersBestFormatInHighLabel() {
        assertEquals("141", AudioQualitySelector.select(formats, "high")?.get("itag")?.toString())
    }

    @Test fun ultraChoosesHighestBitrateAvailable() {
        assertEquals("999", AudioQualitySelector.select(formats, "ultra")?.get("itag")?.toString())
    }

    @Test fun missingQualityLabelsFallBackToBitrateRanks() {
        val unlabeled = Json.parseToJsonElement("""[
            {"itag":1,"bitrate":64000,"mimeType":"audio/webm; codecs=opus"},
            {"itag":2,"bitrate":128000,"mimeType":"audio/mp4; codecs=mp4a.40.2"},
            {"itag":3,"bitrate":256000,"mimeType":"audio/mp4; codecs=mp4a.40.2"}
        ]""").jsonArray.map { it.jsonObject }
        assertEquals("1", AudioQualitySelector.select(unlabeled, "low")?.get("itag")?.toString())
        assertEquals("2", AudioQualitySelector.select(unlabeled, "medium")?.get("itag")?.toString())
        assertEquals("3", AudioQualitySelector.select(unlabeled, "high")?.get("itag")?.toString())
        assertEquals("3", AudioQualitySelector.select(unlabeled, "ultra")?.get("itag")?.toString())
    }

    @Test fun emptyFormatListReturnsNull() {
        assertNull(AudioQualitySelector.select(emptyList(), "high"))
    }
}
