package com.streamcloud.app.data.nuvio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for TMDB API query-parameter normalisation.
 *
 * Covers the two documented plugin bugs that caused "No streams" errors:
 *   1. `append_to _response` (stray space in key) → `append_to_response`
 *   2. `api_kev` (typo) → `api_key`
 */
class NuvioTmdbApiQueryTest {

    // ── append_to_response space bug ────────────────────────────────────────

    @Test
    fun fixesStraySpaceInAppendToResponseKey() {
        val input  = "append_to _response=external_ids&language=en&api_key=abc123"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("append_to_response=")) {
            "Expected 'append_to_response=' in output but got: $result"
        }
        assert(!result.contains("append_to%20_response")) {
            "Output must not contain URL-encoded space in key: $result"
        }
        assert(!result.contains("append_to _response")) {
            "Output must not contain literal space in key: $result"
        }
    }

    @Test
    fun preservesValueWhenFixingAppendToResponseKey() {
        val input  = "append_to _response=external_ids&language=en&api_key=abc123"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("external_ids")) {
            "Value 'external_ids' must be preserved: $result"
        }
    }

    // ── api_kev typo bug ────────────────────────────────────────────────────

    @Test
    fun fixesApiKevTypo() {
        val input  = "api_kev=deadbeef1234"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.startsWith("api_key=")) {
            "Expected 'api_key=' after typo fix but got: $result"
        }
    }

    @Test
    fun preservesApiKeyValueWhenFixingTypo() {
        val input  = "api_kev=1865f43a0549ca50d341dd9ab"
        val result = sanitizeTmdbApiQueryString(input)
        assertEquals("api_key=1865f43a0549ca50d341dd9ab", result)
    }

    @Test
    fun doesNotRenameCorrectApiKey() {
        val input  = "api_key=validkey&language=en"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("api_key=validkey")) {
            "Correct 'api_key' must not be renamed: $result"
        }
    }

    // ── combined query string ───────────────────────────────────────────────

    @Test
    fun fixesBothBugsInSingleQueryString() {
        // Simulates the Multivid-style malformed URL query
        val input  = "append_to _response=external_ids&language=en&api_key=abc123"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("append_to_response=external_ids")) {
            "Expected fixed key and preserved value: $result"
        }
        assert(result.contains("api_key=abc123")) {
            "api_key must be preserved unchanged: $result"
        }
    }

    @Test
    fun fixesLeadingSpaceInParamNameLikeTorrentioCase() {
        // Simulates the Torrentio " api_kev=" (leading space before key)
        val input  = " api_kev=1865f43a0549ca50d341dd9ab&language=en"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("api_key=1865f43a0549ca50d341dd9ab")) {
            "Expected leading-space + typo to both be fixed: $result"
        }
    }

    // ── TMDB URL path structure ────────────────────────────────────────────

    @Test
    fun tmdbMovieIdIsNumericAndNonZero() {
        // Regression: the film reported in the bug (id 1314481) must be valid
        val id = sanitizeNuvioTmdbId("1314481")
        assertEquals("1314481", id)
    }

    @Test
    fun tmdbMovieId687163IsValid() {
        // Regression: second film reported in the bug (id 687163) must be valid
        val id = sanitizeNuvioTmdbId("687163")
        assertEquals("687163", id)
    }

    @Test
    fun sanitizeTmdbApiQueryStringPreservesEmptyString() {
        assertEquals("", sanitizeTmdbApiQueryString(""))
    }

    @Test
    fun sanitizeTmdbApiQueryStringPreservesUnchangedParams() {
        val input  = "language=en&include_adult=false&page=1"
        val result = sanitizeTmdbApiQueryString(input)
        // All keys are clean; result must be equivalent (keys re-encoded, values preserved)
        assert(result.contains("language=en"))       { "language must be preserved: $result" }
        assert(result.contains("include_adult=false")){ "include_adult must be preserved: $result" }
        assert(result.contains("page=1"))             { "page must be preserved: $result" }
    }

    // ── Cineby / Videasy proxy URL (db.videasy.to) ──────────────────────────

    @Test
    fun fixesAppendToResponseInVideasyStyleQuery() {
        // Regression: Cineby/Videasy provider constructs
        // `?append_to _response=external_ids,genres` which causes HTTP 404.
        val input  = "append_to _response=external_ids,genres"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("append_to_response=")) {
            "Space must be removed from 'append_to_response': $result"
        }
        assert(result.contains("external_ids")) {
            "Value 'external_ids' must be preserved: $result"
        }
        assert(result.contains("genres")) {
            "Value 'genres' must be preserved: $result"
        }
        assert(!result.contains("append_to%20_response") && !result.contains("append_to _response")) {
            "No space (literal or encoded) must remain in the key: $result"
        }
    }

    @Test
    fun fixesFullVideasyStyleQueryString() {
        // Full query from the reported 404: append_to _response=external_ids,genres
        val input  = "append_to _response=external_ids,genres&language=en&api_key=validkey123"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("append_to_response=external_ids,genres")) {
            "Key must be normalized and value preserved: $result"
        }
        assert(result.contains("api_key=validkey123")) {
            "api_key must pass through unchanged: $result"
        }
    }

    // ── Torrentio / leading-space-before-key (Torrentio bug) ───────────────

    @Test
    fun fixesFullTorrentioStyleQueryString() {
        // Simulates the Torrentio-style query: " api_kev=<key>&language=en"
        // reported as `[rsp GET /3/movie/687163? api_kev=1865f43a0549ca50d341dd9ab8 ->200`
        val input  = " api_kev=1865f43a0549ca50d341dd9ab8&language=en"
        val result = sanitizeTmdbApiQueryString(input)
        assert(result.contains("api_key=1865f43a0549ca50d341dd9ab8")) {
            "Both leading space and typo must be fixed: $result"
        }
        assert(result.contains("language=en")) {
            "Remaining params must be preserved: $result"
        }
    }
}
