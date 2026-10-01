package com.streamcloud.app.audio

internal object AndroidAutoBrowsePolicy {
    val localTrackParents = setOf(
        MusicPlaybackService.HOME_ID,
        AndroidAutoHomeContent.SPEED_DIAL_ID,
        AndroidAutoHomeContent.QUICK_PICKS_ID,
        MusicPlaybackService.RECENT_ID,
        MusicPlaybackService.ON_REPEAT_ID,
        MusicPlaybackService.LIKED_ID,
        MusicPlaybackService.DOWNLOADED_ID,
    )

    val localPlaylistParents = setOf(MusicPlaybackService.PLAYLISTS_ID)

    val remoteLibraryParents = setOf(
        MusicPlaybackService.HOME_ID,
        MusicPlaybackService.HOME_FEED_ID,
        MusicPlaybackService.LIBRARY_ID,
        MusicPlaybackService.PLAYLISTS_ID,
        MusicPlaybackService.ALBUMS_ID,
        MusicPlaybackService.ARTISTS_ID,
        MusicPlaybackService.LIKED_ID,
    )

    val sectionSettingsParents = setOf(MusicPlaybackService.ROOT_ID)

    val suggestionSettingsParents = setOf(
        MusicPlaybackService.HOME_ID,
        MusicPlaybackService.PLAYLISTS_ID,
    )
}