package com.streamcloud.app.audio

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoReliabilityPolicyTest {

    @Test
    fun localTrackChangesInvalidateEveryHomeAndTrackShelf() {
        assertEquals(
            setOf(
                MusicPlaybackService.HOME_ID,
                AndroidAutoHomeContent.SPEED_DIAL_ID,
                AndroidAutoHomeContent.QUICK_PICKS_ID,
                MusicPlaybackService.RECENT_ID,
                MusicPlaybackService.ON_REPEAT_ID,
                MusicPlaybackService.LIKED_ID,
                MusicPlaybackService.DOWNLOADED_ID,
            ),
            AndroidAutoBrowsePolicy.localTrackParents,
        )
    }

    @Test
    fun remoteLibraryAndSettingChangesInvalidateTheirBrowseParents() {
        assertTrue(AndroidAutoBrowsePolicy.remoteLibraryParents.containsAll(
            setOf(
                MusicPlaybackService.HOME_ID,
                MusicPlaybackService.HOME_FEED_ID,
                MusicPlaybackService.LIBRARY_ID,
                MusicPlaybackService.PLAYLISTS_ID,
                MusicPlaybackService.ALBUMS_ID,
                MusicPlaybackService.ARTISTS_ID,
                MusicPlaybackService.LIKED_ID,
            ),
        ))
        assertEquals(setOf(MusicPlaybackService.ROOT_ID), AndroidAutoBrowsePolicy.sectionSettingsParents)
        assertEquals(
            setOf(MusicPlaybackService.HOME_ID, MusicPlaybackService.PLAYLISTS_ID),
            AndroidAutoBrowsePolicy.suggestionSettingsParents,
        )
        assertEquals(setOf(MusicPlaybackService.PLAYLISTS_ID), AndroidAutoBrowsePolicy.localPlaylistParents)
    }

    @Test
    fun queueWindowAlwaysIncludesActiveTrackAndKeepsUpcomingItems() {
        val window = androidAutoQueueWindow(itemCount = 250, currentIndex = 180, maxItems = 100)

        assertNotNull(window)
        assertEquals(100, window!!.endExclusive - window.startIndex)
        assertEquals(180, window.startIndex + window.currentIndex)
        assertTrue(window.currentIndex <= 25)
        assertTrue(window.endExclusive > 180)
        val firstTrack = androidAutoQueueWindow(itemCount = 250, currentIndex = 0, maxItems = 100)!!
        assertEquals(0, firstTrack.currentIndex)
        assertEquals(0, firstTrack.startIndex)
        val lastTrack = androidAutoQueueWindow(itemCount = 250, currentIndex = 249, maxItems = 100)!!
        assertEquals(249, lastTrack.startIndex + lastTrack.currentIndex)
        assertEquals(250, lastTrack.endExclusive)
        assertNull(androidAutoQueueWindow(itemCount = 0, currentIndex = 0, maxItems = 100))
    }

    @Test
    fun repeatModeRestoreRejectsInvalidValuesAndUriPersistenceExcludesRemoteStreams() {
        assertEquals(Player.REPEAT_MODE_OFF, normalizedRepeatMode(-1))
        assertEquals(Player.REPEAT_MODE_ONE, normalizedRepeatMode(Player.REPEAT_MODE_ONE))
        assertEquals(Player.REPEAT_MODE_ALL, normalizedRepeatMode(Player.REPEAT_MODE_ALL))

        assertTrue(canPersistLocalPlaybackUri("content://media/external/audio/42"))
        assertTrue(canPersistLocalPlaybackUri("file:///data/user/0/app/song.mp3"))
        assertTrue(canPersistLocalPlaybackUri("android.resource://com.streamcloud/raw/theme"))
        assertFalse(canPersistLocalPlaybackUri("https://rr.googlevideo.com/signed-stream"))
        assertFalse(canPersistLocalPlaybackUri(null))
    }

    @Test
    fun likedItemsDeduplicateByYoutubeVideoIdAndPreferAccountArtwork() {
        val liked = AndroidAutoLikedContent.mergePreferFirst(
            preferred = listOf("https://music.youtube.com/watch?v=abcdefghijk"),
            fallback = listOf(
                "https://www.youtube.com/watch?v=abcdefghijk",
                "content://media/external/audio/42",
            ),
            identity = { AndroidAutoLikedContent.identity(it) },
        )

        assertEquals(
            listOf(
                "https://music.youtube.com/watch?v=abcdefghijk",
                "content://media/external/audio/42",
            ),
            liked,
        )
    }

    @Test
    fun voiceSearchSeparatesNoMatchesFromProviderFailure() {
        val noMatches = androidAutoSearchOutcome(emptyList<String>(), anyProviderSucceeded = true)
        val unavailable = androidAutoSearchOutcome(emptyList<String>(), anyProviderSucceeded = false)
        val found = androidAutoSearchOutcome(listOf("song"), anyProviderSucceeded = false)

        assertEquals(AndroidAutoSearchState.NO_MATCHES, noMatches.state)
        assertEquals(0, noMatches.browserResultCount)
        assertEquals(AndroidAutoSearchState.PROVIDERS_UNAVAILABLE, unavailable.state)
        assertEquals(1, unavailable.browserResultCount)
        assertTrue(androidAutoSearchMessage(unavailable.state, "jazz").contains("connection"))
        assertTrue(androidAutoSearchMessage(noMatches.state, "jazz").contains("jazz"))
        assertEquals(AndroidAutoSearchState.RESULTS, found.state)
        assertEquals(1, found.browserResultCount)
    }
}