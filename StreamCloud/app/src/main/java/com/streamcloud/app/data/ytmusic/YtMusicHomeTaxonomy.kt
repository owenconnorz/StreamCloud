package com.streamcloud.app.data.ytmusic

object YtMusicHomeTaxonomy {

    private val orderedTitles = listOf(
        "Quick Picks",
        "Recommended for You",
        "New Releases",
        "Trending Now",
        "Top Songs",
        "Top Albums",
        "Top Artists",
        "Mood & Genres",
        "Featured Playlists",
        "Your Mixes",
        "Music Videos",
    )

    fun mapSections(rawSections: List<HomeSection>, fallbackSongs: List<YtmSong>): List<HomeSection> {
        val fallback = fallbackSongs.distinctBy { it.videoId }

        val unusedPlaylistRails = rawSections
            .filterIsInstance<HomeSection.PlaylistRail>()
            .toMutableList()
        val unusedSongRails = rawSections
            .filterIsInstance<HomeSection.SongRail>()
            .toMutableList()
        val moodChips = rawSections
            .filterIsInstance<HomeSection.MoodChips>()
            .flatMap { it.chips }
            .distinctBy { it.label }
        val defaultMoodChips = listOf(
            "Chill", "Workout", "Focus", "Party", "Sleep",
        ).map { MoodChip(label = it, params = null) }

        fun takePlaylistBy(vararg keywords: String): HomeSection.PlaylistRail? {
            val idx = unusedPlaylistRails.indexOfFirst { rail ->
                rail.title.containsAny(keywords)
            }
            return when {
                idx >= 0 -> unusedPlaylistRails.removeAt(idx)
                unusedPlaylistRails.isNotEmpty() -> unusedPlaylistRails.removeAt(0)
                else -> null
            }
        }

        fun takeSongBy(vararg keywords: String): HomeSection.SongRail? {
            val idx = unusedSongRails.indexOfFirst { rail ->
                rail.title.containsAny(keywords)
            }
            return when {
                idx >= 0 -> unusedSongRails.removeAt(idx)
                unusedSongRails.isNotEmpty() -> unusedSongRails.removeAt(0)
                else -> null
            }
        }

        val quickPicks = (takeSongBy("quick", "pick")
            ?: fallbackSongRail(orderedTitles[0], fallback.take(20)))
            .withTitle(orderedTitles[0])

        val recommended = (takePlaylistBy("for you", "recommended", "listen again")
            ?: takeSongBy("for you", "recommended")?.takeItems(20)
            ?: fallbackSongRail(orderedTitles[1], fallback.take(20)))
            .withTitle(orderedTitles[1])

        val newReleases = (takePlaylistBy("new release", "new")
            ?: takeSongBy("new release", "new")?.takeItems(20)
            ?: fallbackSongRail(orderedTitles[2], fallback.take(20)))
            .withTitle(orderedTitles[2])

        val trending = (takeSongBy("trending", "viral")
            ?: fallbackSongRail(orderedTitles[3], fallback.take(20)))
            .withTitle(orderedTitles[3])

        val topSongs = (takeSongBy("top", "hits", "chart")
            ?: fallbackSongRail(orderedTitles[4], fallback.take(20)))
            .withTitle(orderedTitles[4])

        val topAlbums = (takePlaylistBy("album", "release")
            ?: unusedPlaylistRails.firstOrNull()?.also { unusedPlaylistRails.remove(it) }
            ?: fallbackSongRail(orderedTitles[5], fallback.take(20)))
            .withTitle(orderedTitles[5])

        val topArtists = takeSongBy("artist")
            ?.let { rail -> rail.copy(items = distinctByArtist(rail.items, max = 20)) }
            ?.withTitle(orderedTitles[6])
            ?: HomeSection.SongRail(orderedTitles[6], distinctByArtist(fallback, max = 20))

        val moodAndGenres = HomeSection.MoodChips(
            title = orderedTitles[7],
            chips = if (moodChips.isNotEmpty()) moodChips else defaultMoodChips,
        )

        val featuredPlaylists = (takePlaylistBy("playlist", "featured")
            ?: takeSongBy("playlist")?.takeItems(20)
            ?: fallbackSongRail(orderedTitles[8], fallback.take(20)))
            .withTitle(orderedTitles[8])

        val yourMixes = (takePlaylistBy("mix")
            ?: takeSongBy("mix")?.takeItems(20)
            ?: fallbackSongRail(orderedTitles[9], fallback.take(20)))
            .withTitle(orderedTitles[9])

        val videoPlaylist = rawSections
            .filterIsInstance<HomeSection.PlaylistRail>()
            .firstOrNull { rail -> rail.items.any { it.isVideo } }
            ?.let { rail -> rail.copy(items = rail.items.filter { item -> item.isVideo }) }
        val videoSongs = rawSections
            .filterIsInstance<HomeSection.SongRail>()
            .firstOrNull { rail -> rail.items.any { it.isVideo } }
            ?.items
            ?.take(20)
            .orEmpty()
            .ifEmpty { fallback.filter { it.isVideo }.take(20) }
            .ifEmpty { fallback.take(20) }
        val musicVideos = (videoPlaylist?.takeItems(20)
            ?: HomeSection.SongRail(orderedTitles[10], videoSongs))
            .withTitle(orderedTitles[10])

        return listOf(
            quickPicks,
            recommended,
            newReleases,
            trending,
            topSongs,
            topAlbums,
            topArtists,
            moodAndGenres,
            featuredPlaylists,
            yourMixes,
            musicVideos,
        )
    }

    private fun fallbackSongRail(title: String, songs: List<YtmSong>): HomeSection.SongRail {
        return HomeSection.SongRail(title = title, items = songs)
    }

    private fun HomeSection.withTitle(title: String): HomeSection = when (this) {
        is HomeSection.PlaylistRail -> copy(title = title)
        is HomeSection.SongRail -> copy(title = title)
        is HomeSection.MoodChips -> copy(title = title)
    }

    private fun HomeSection.PlaylistRail.takeItems(max: Int): HomeSection.PlaylistRail =
        copy(items = items.take(max))

    private fun HomeSection.SongRail.takeItems(max: Int): HomeSection.SongRail =
        copy(items = items.take(max))

    private fun String.containsAny(keywords: Array<out String>): Boolean {
        if (keywords.isEmpty()) return false
        return keywords.any { keyword -> contains(keyword, ignoreCase = true) }
    }

    private fun distinctByArtist(songs: List<YtmSong>, max: Int): List<YtmSong> {
        return songs.distinctBy { song -> song.artist.lowercase() }.take(max)
    }
}
