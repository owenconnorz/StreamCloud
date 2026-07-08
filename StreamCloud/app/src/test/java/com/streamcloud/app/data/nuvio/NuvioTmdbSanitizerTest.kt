package com.streamcloud.app.data.nuvio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NuvioTmdbSanitizerTest {

    @Test
    fun trimsWhitespaceFromMovieTmdbIds() {
        assertEquals("1169516", sanitizeNuvioTmdbId("  1169516  "))
    }

    @Test
    fun convertsNumericTmdbIdsToStringsSafely() {
        assertEquals("1169516", sanitizeNuvioTmdbId(1169516))
    }

    @Test
    fun returnsNullForInvalidTmdbIds() {
        assertNull(sanitizeNuvioTmdbId("movie: abc "))
    }

    @Test
    fun normalizesTmdbPrefixedEpisodeIdsBeforeExecution() {
        assertEquals("687163", normaliseNuvioContentId("tmdb:687163:1:2", season = 1, episode = 2))
    }

    @Test
    fun normalizesPathStyleEpisodeIdsBeforeExecution() {
        assertEquals("687163", normaliseNuvioContentId("tmdb/687163/1/2", season = 1, episode = 2))
    }
}
