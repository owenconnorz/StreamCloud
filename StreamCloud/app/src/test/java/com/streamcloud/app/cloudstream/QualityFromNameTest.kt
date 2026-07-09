package com.streamcloud.app.cloudstream

import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the improved [getQualityFromName] quality-string resolution.
 *
 * Verifies that common quality strings used by CloudStream plugins are mapped to
 * the correct [Qualities] value, including new aliases (uhd, fhd, hd, sd, qhd)
 * added to close the gap with recloudstream/cloudstream.
 */
class QualityFromNameTest {

    @Test fun numericString1080ReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("1080"))

    @Test fun numericString1080pReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("1080p"))

    @Test fun numericString720ReturnsP720() =
        assertEquals(Qualities.P720.value, getQualityFromName("720"))

    @Test fun numericString720pReturnsP720() =
        assertEquals(Qualities.P720.value, getQualityFromName("720p"))

    @Test fun numericString480pReturnsP480() =
        assertEquals(Qualities.P480.value, getQualityFromName("480p"))

    @Test fun numericString360pReturnsP360() =
        assertEquals(Qualities.P360.value, getQualityFromName("360p"))

    @Test fun numericString240pReturnsP240() =
        assertEquals(Qualities.P240.value, getQualityFromName("240p"))

    @Test fun numericString144pReturnsP144() =
        assertEquals(Qualities.P144.value, getQualityFromName("144p"))

    // 4K variants
    @Test fun fourKStringReturnsP2160() =
        assertEquals(Qualities.P2160.value, getQualityFromName("4k"))

    @Test fun uhdStringReturnsP2160() =
        assertEquals(Qualities.P2160.value, getQualityFromName("uhd"))

    @Test fun uhdUppercaseReturnsP2160() =
        assertEquals(Qualities.P2160.value, getQualityFromName("UHD"))

    @Test fun numericString2160pReturnsP2160() =
        assertEquals(Qualities.P2160.value, getQualityFromName("2160p"))

    // QHD / 1440p
    @Test fun qhdStringReturnsP1440() =
        assertEquals(Qualities.P1440.value, getQualityFromName("qhd"))

    @Test fun twoKStringReturnsP1440() =
        assertEquals(Qualities.P1440.value, getQualityFromName("2k"))

    @Test fun numericString1440pReturnsP1440() =
        assertEquals(Qualities.P1440.value, getQualityFromName("1440p"))

    // Full HD aliases
    @Test fun fhdStringReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("fhd"))

    @Test fun fhdUppercaseReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("FHD"))

    @Test fun fullHdStringReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("full hd"))

    @Test fun fullHdCompactStringReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("fullhd"))

    @Test fun interlaced1080iReturnsP1080() =
        assertEquals(Qualities.P1080.value, getQualityFromName("1080i"))

    // HD aliases
    @Test fun hdStringReturnsP720() =
        assertEquals(Qualities.P720.value, getQualityFromName("hd"))

    @Test fun hdUppercaseReturnsP720() =
        assertEquals(Qualities.P720.value, getQualityFromName("HD"))

    // SD aliases
    @Test fun sdStringReturnsP480() =
        assertEquals(Qualities.P480.value, getQualityFromName("sd"))

    @Test fun sdUppercaseReturnsP480() =
        assertEquals(Qualities.P480.value, getQualityFromName("SD"))

    // Unknowns
    @Test fun nullInputReturnsUnknown() =
        assertEquals(Qualities.Unknown.value, getQualityFromName(null))

    @Test fun randomStringReturnsUnknown() =
        assertEquals(Qualities.Unknown.value, getQualityFromName("auto"))
}
