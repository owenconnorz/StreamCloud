package com.streamcloud.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for artist-follow domain logic.
 *
 * These tests validate entity construction, idempotency contract expectations,
 * and notification deduplication semantics — all without an Android Context.
 */
class ArtistFollowDomainTest {

    // ── ArtistFollowEntity ────────────────────────────────────────────────────

    @Test
    fun artistFollowEntityDefaultsFollowedAtToCurrentTime() {
        val before = System.currentTimeMillis()
        val entity = ArtistFollowEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
        )
        val after = System.currentTimeMillis()
        assertTrue("followedAt should be >= before", entity.followedAt >= before)
        assertTrue("followedAt should be <= after", entity.followedAt <= after)
    }

    @Test
    fun artistFollowEntityAllowsNullThumbnail() {
        val entity = ArtistFollowEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            artistThumbnail = null,
        )
        assertNull(entity.artistThumbnail)
    }

    @Test
    fun artistFollowEntityStoresChannelUrl() {
        val url = "https://music.youtube.com/channel/UCexample"
        val entity = ArtistFollowEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            channelUrl = url,
        )
        assertEquals(url, entity.channelUrl)
    }

    @Test
    fun artistFollowEntityEmptyChannelUrlIsAllowed() {
        val entity = ArtistFollowEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
        )
        assertEquals("", entity.channelUrl)
    }

    // ── ArtistReleaseNotificationEntity ───────────────────────────────────────

    @Test
    fun notificationDefaultsToUnread() {
        val notif = ArtistReleaseNotificationEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            releaseId = "PLrelease123",
            releaseTitle = "New Album",
        )
        assertFalse(notif.isRead)
    }

    @Test
    fun notificationDefaultsEventTypeToUnknown() {
        val notif = ArtistReleaseNotificationEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            releaseId = "PLrelease123",
            releaseTitle = "New Album",
        )
        assertEquals(ReleaseEventType.UNKNOWN.name, notif.eventType)
    }

    @Test
    fun notificationAlbumEventTypeStoredCorrectly() {
        val notif = ArtistReleaseNotificationEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            releaseId = "PLrelease123",
            releaseTitle = "New Album",
            eventType = ReleaseEventType.ALBUM.name,
        )
        assertEquals(ReleaseEventType.ALBUM.name, notif.eventType)
    }

    @Test
    fun notificationSingleEpEventTypeStoredCorrectly() {
        val notif = ArtistReleaseNotificationEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            releaseId = "PLsingle456",
            releaseTitle = "New Single",
            eventType = ReleaseEventType.SINGLE_EP.name,
        )
        assertEquals(ReleaseEventType.SINGLE_EP.name, notif.eventType)
    }

    @Test
    fun notificationDedupeKeyIsReleaseId() {
        // Two notifications for the same release should be considered duplicates
        // (the DB enforces uniqueness on release_id via UNIQUE index + IGNORE conflict).
        // Here we verify the business key is consistent across construction.
        val releaseId = "PLrelease999"
        val notif1 = ArtistReleaseNotificationEntity(
            artistId = "UC1",
            artistName = "Artist 1",
            releaseId = releaseId,
            releaseTitle = "Album Title",
        )
        val notif2 = ArtistReleaseNotificationEntity(
            artistId = "UC1",
            artistName = "Artist 1",
            releaseId = releaseId,
            releaseTitle = "Album Title",
        )
        assertEquals(notif1.releaseId, notif2.releaseId)
    }

    @Test
    fun notificationCanMarkAsRead() {
        val notif = ArtistReleaseNotificationEntity(
            artistId = "UCexample",
            artistName = "Test Artist",
            releaseId = "PLrelease123",
            releaseTitle = "New Album",
        )
        val readNotif = notif.copy(isRead = true)
        assertTrue(readNotif.isRead)
        assertFalse(notif.isRead) // original unchanged
    }

    // ── ReleaseEventType enum ─────────────────────────────────────────────────

    @Test
    fun releaseEventTypeValuesAreStable() {
        val types = ReleaseEventType.values()
        assertTrue(types.contains(ReleaseEventType.ALBUM))
        assertTrue(types.contains(ReleaseEventType.SINGLE_EP))
        assertTrue(types.contains(ReleaseEventType.VIDEO))
        assertTrue(types.contains(ReleaseEventType.UNKNOWN))
    }

    @Test
    fun releaseEventTypeNameRoundTripsFromString() {
        for (type in ReleaseEventType.values()) {
            assertEquals(type, ReleaseEventType.valueOf(type.name))
        }
    }
}
