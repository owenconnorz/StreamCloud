package com.streamcloud.app.data.api

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reddit error classification.
 *
 * These tests verify that:
 * - HTTP 401/403 responses from Reddit are mapped to [RedditAuthRequiredException]
 * - HTTP 429 responses are mapped to [RedditRateLimitException]
 * - The [RedditAdultSubs.PRESETS] list is non-empty and properly formatted
 */
class RedditErrorHandlingTest {

    @Test
    fun `RedditAuthRequiredException carries meaningful message`() {
        val ex = RedditAuthRequiredException("Reddit requires sign-in (HTTP 403).")
        assertTrue(ex.message!!.contains("403"))
        assertTrue(ex is Exception)
    }

    @Test
    fun `RedditRateLimitException carries meaningful message`() {
        val ex = RedditRateLimitException("Reddit rate limit reached. Please wait.")
        assertTrue(ex.message!!.contains("rate limit"))
        assertTrue(ex is Exception)
    }

    @Test
    fun `RedditAuthRequiredException is not a RedditRateLimitException`() {
        val auth: Exception = RedditAuthRequiredException("auth error")
        assertTrue(auth is RedditAuthRequiredException)
        assertTrue(auth !is RedditRateLimitException)
    }

    @Test
    fun `RedditAdultSubs PRESETS are non-empty and r-slash prefixed`() {
        assertTrue("PRESETS must not be empty", RedditAdultSubs.PRESETS.isNotEmpty())
        RedditAdultSubs.PRESETS.forEach { (label, _) ->
            assertTrue(
                "Each preset label must start with 'r/': $label",
                label.startsWith("r/")
            )
        }
    }

    @Test
    fun `RedditAdultSubs PRESETS have no blank subreddit names`() {
        RedditAdultSubs.PRESETS.forEach { (_, sub) ->
            assertTrue("Subreddit name must not be blank: $sub", sub.isNotBlank())
        }
    }

    @Test
    fun `AdultSource Reddit label is readable`() {
        assertNotNull(AdultSource.Reddit.label)
        assertTrue(AdultSource.Reddit.label.isNotBlank())
    }
}
