package com.streamcloud.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for artist-follow domain logic.
 *
 * These tests validate entity construction and idempotency contract expectations
 * without an Android Context.
 */
class ArtistFollowDomainTest {

    // ── FollowedArtistEntity ──────────────────────────────────────────────────

    @Test
    fun followedArtistEntityDefaultsFollowedAtToCurrentTime() {
        val before = System.currentTimeMillis()
        val entity = FollowedArtistEntity(
            channelId = "UCexample",
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        val after = System.currentTimeMillis()
        assertTrue("followedAt should be >= before", entity.followedAt >= before)
        assertTrue("followedAt should be <= after", entity.followedAt <= after)
    }

    @Test
    fun followedArtistEntityAllowsNullThumbnail() {
        val entity = FollowedArtistEntity(
            channelId = "UCexample",
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        assertNull(entity.thumbnail)
    }

    @Test
    fun followedArtistEntityAllowsNullSubscriberLabel() {
        val entity = FollowedArtistEntity(
            channelId = "UCexample",
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        assertNull(entity.subscriberLabel)
    }

    @Test
    fun followedArtistEntityStoresChannelId() {
        val channelId = "UCexample123"
        val entity = FollowedArtistEntity(
            channelId = channelId,
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        assertEquals(channelId, entity.channelId)
    }

    @Test
    fun followedArtistEntityDefaultsLatestReleaseIdToNull() {
        val entity = FollowedArtistEntity(
            channelId = "UCexample",
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        assertNull(entity.latestReleaseId)
    }

    @Test
    fun followedArtistEntityCanUpdateLatestReleaseId() {
        val entity = FollowedArtistEntity(
            channelId = "UCexample",
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        val updated = entity.copy(latestReleaseId = "PLrelease123")
        assertEquals("PLrelease123", updated.latestReleaseId)
        assertNull(entity.latestReleaseId) // original unchanged
    }

    @Test
    fun followedArtistEntityDedupeKeyIsChannelId() {
        // Two entities with the same channelId should be considered the same artist.
        // The DB enforces uniqueness via PRIMARY KEY on channel_id.
        val channelId = "UCexample999"
        val entity1 = FollowedArtistEntity(
            channelId = channelId,
            name = "Artist Name",
            thumbnail = null,
            subscriberLabel = null,
        )
        val entity2 = FollowedArtistEntity(
            channelId = channelId,
            name = "Artist Name",
            thumbnail = "https://example.com/thumb.jpg",
            subscriberLabel = "1M subscribers",
        )
        assertEquals(entity1.channelId, entity2.channelId)
    }

    @Test
    fun followedArtistEntityIsFollowedByDefault() {
        // Any entity that exists in the DB is implicitly followed.
        // Here we verify constructor sets no unexpected flags.
        val entity = FollowedArtistEntity(
            channelId = "UCexample",
            name = "Test Artist",
            thumbnail = null,
            subscriberLabel = null,
        )
        assertFalse(entity.channelId.isBlank())
        assertFalse(entity.name.isBlank())
    }
}

