package com.streamcloud.app.audio

import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.YtMusicHomeFeed

internal data class AndroidAutoHomeShelf(
    val mediaId: String,
    val title: String,
    val artworkUrl: String?,
)

internal object AndroidAutoHomeContent {
    const val SPEED_DIAL_ID = "streamcloud_home_speed_dial"
    const val QUICK_PICKS_ID = "streamcloud_home_quick_picks"

    private const val SPEED_DIAL_LIMIT = 8
    private const val QUICK_PICKS_LIMIT = 20

    fun speedDialTracks(
        recent: List<TrackEntity>,
        liked: List<TrackEntity>,
        mostPlayed: List<TrackEntity>,
    ): List<TrackEntity> =
        mergeDistinct(recent, liked, mostPlayed.filter { it.playCount > 0 }).take(SPEED_DIAL_LIMIT)

    fun quickPickTracks(
        mostPlayed: List<TrackEntity>,
        recent: List<TrackEntity>,
        liked: List<TrackEntity>,
    ): List<TrackEntity> =
        mergeDistinct(mostPlayed.filter { it.playCount > 0 }, recent, liked).take(QUICK_PICKS_LIMIT)

    fun artworkUrl(tracks: List<TrackEntity>): String? =
        tracks.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) }

    fun feedShelves(feed: YtMusicHomeFeed): List<AndroidAutoHomeShelf> =
        feed.sections.mapIndexedNotNull { index, section ->
            val title: String
            val artworkUrl: String?
            val hasItems: Boolean
            when (section) {
                is HomeSection.PlaylistRail -> {
                    title = section.title
                    artworkUrl = section.items.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) }
                    hasItems = section.items.isNotEmpty()
                }
                is HomeSection.SongRail -> {
                    title = section.title
                    artworkUrl = section.items.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) }
                    hasItems = section.items.isNotEmpty()
                }
                is HomeSection.MoodChips -> return@mapIndexedNotNull null
            }
            if (!hasItems || title.isBlank()) return@mapIndexedNotNull null
            AndroidAutoHomeShelf(
                mediaId = "${MusicPlaybackService.YT_HOME_SECTION_PREFIX}$index",
                title = title,
                artworkUrl = artworkUrl,
            )
        }

    fun hasQuickPicksShelf(shelves: List<AndroidAutoHomeShelf>): Boolean =
        shelves.any { it.title.contains("quick", ignoreCase = true) || it.title.contains("pick", ignoreCase = true) }

    private fun mergeDistinct(vararg groups: List<TrackEntity>): List<TrackEntity> =
        groups.asSequence()
            .flatten()
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .toList()
}