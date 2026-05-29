package com.streamcloud.app.data.newpipe

import com.streamcloud.app.data.util.hqYtThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YtTrack(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val url: String,
    val thumbnail: String?,

    val isVideo: Boolean = false,
)

data class YtAlbum(
    val title: String,
    val artist: String,
    val url: String,
    val thumbnail: String?,
    val year: String? = null,
)

data class YtArtist(
    val name: String,
    val url: String,
    val thumbnail: String?,
    val subscriberLabel: String? = null,
)

data class MusicSearchSections(
    val topResult: YtTrack? = null,
    val songs: List<YtTrack> = emptyList(),
    val videos: List<YtTrack> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val artists: List<YtArtist> = emptyList(),
)

object NewPipeRepository {


    suspend fun searchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            ServiceList.YouTube.suggestionExtractor.suggestionList(query)
        } catch (_: Exception) {
            emptyList()
        }
    }


    suspend fun searchMusic(query: String): List<YtTrack> = searchSongs(query)

    suspend fun searchSongs(query: String): List<YtTrack> = searchTracks(query, "music_songs", isVideo = false)
    suspend fun searchVideos(query: String): List<YtTrack> = searchTracks(query, "music_videos", isVideo = true)

    private suspend fun searchTracks(query: String, filter: String, isVideo: Boolean): List<YtTrack> =
        withContext(Dispatchers.IO) {
            val service = ServiceList.YouTube
            val info = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(query, listOf(filter), ""),
            )
            info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(isVideo) }
        }

    suspend fun searchAlbums(query: String): List<YtAlbum> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val info = SearchInfo.getInfo(
            service,
            service.searchQHFactory.fromQuery(query, listOf("music_albums"), ""),
        )
        info.relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { item ->
            val url = item.url ?: return@mapNotNull null
            YtAlbum(
                title = item.name ?: "Untitled",
                artist = item.uploaderName.orEmpty(),
                url = url,
                thumbnail = item.thumbnails?.lastOrNull()?.url?.hqYtThumb(720),
            )
        }
    }

    suspend fun searchArtists(query: String): List<YtArtist> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val info = SearchInfo.getInfo(
            service,
            service.searchQHFactory.fromQuery(query, listOf("music_artists"), ""),
        )
        info.relatedItems.filterIsInstance<ChannelInfoItem>().mapNotNull { item ->
            val url = item.url ?: return@mapNotNull null
            YtArtist(
                name = item.name ?: "Untitled",
                url = url,
                thumbnail = item.thumbnails?.lastOrNull()?.url?.hqYtThumb(720),
                subscriberLabel = item.subscriberCount.takeIf { it >= 0 }
                    ?.let { humanCount(it) + " subscribers" },
            )
        }
    }


    data class ArtistPage(
        val name: String,
        val avatar: String?,
        val banner: String?,
        val description: String,
        val subscriberLabel: String?,
        val viewCount: Long = 0L,
        val topTracks: List<YtTrack>,
        val albums: List<YtAlbum>,
        val videos: List<YtTrack> = emptyList(),
    )

    suspend fun loadArtist(channelUrl: String): ArtistPage? = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val info = runCatching {
            org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(service, channelUrl)
        }.getOrNull() ?: return@withContext null

        // Parallel: tab 0 = popular tracks, tab 1 = playlists/albums
        val (tracks, albums) = coroutineScope {
            val tracksJob = async {
                runCatching {
                    val tab = info.tabs.firstOrNull() ?: return@runCatching emptyList<YtTrack>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(service, tab)
                    tabInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                        .mapNotNull { it.toTrack(isVideo = false) }
                        .take(20)
                }.getOrDefault(emptyList())
            }
            val albumsJob = async {
                runCatching {
                    val tab = info.tabs.getOrNull(1) ?: return@runCatching emptyList<YtAlbum>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(service, tab)
                    tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { item ->
                        val url = item.url ?: return@mapNotNull null
                        YtAlbum(
                            title = item.name ?: "Untitled",
                            artist = item.uploaderName.orEmpty(),
                            url = url,
                            thumbnail = item.thumbnails?.lastOrNull()?.url?.hqYtThumb(720),
                        )
                    }.take(10)
                }.getOrDefault(emptyList())
            }
            Pair(tracksJob.await(), albumsJob.await())
        }

        ArtistPage(
            name = info.name ?: "",
            avatar = info.avatars?.lastOrNull()?.url,
            banner = info.banners?.lastOrNull()?.url,
            description = info.description.orEmpty(),
            subscriberLabel = info.subscriberCount.takeIf { it >= 0 }
                ?.let { humanCount(it) + " subscribers" },
            viewCount = runCatching { info.viewCount }.getOrDefault(-1L).let { if (it >= 0) it else 0L },
            topTracks = tracks,
            albums = albums,
            videos = tracks.take(6),
        )
    }


    suspend fun searchAll(query: String): MusicSearchSections = coroutineScope {
        val songsJob = async { runCatching { searchSongs(query) }.getOrDefault(emptyList()) }
        val videosJob = async { runCatching { searchVideos(query) }.getOrDefault(emptyList()) }
        val albumsJob = async { runCatching { searchAlbums(query) }.getOrDefault(emptyList()) }
        val artistsJob = async { runCatching { searchArtists(query) }.getOrDefault(emptyList()) }

        val songs = songsJob.await()
        val videos = videosJob.await()
        val albums = albumsJob.await()
        val artists = artistsJob.await()



        val topArtist = artists.firstOrNull { it.name.equals(query, ignoreCase = true) }
        val top = if (topArtist != null) null else songs.firstOrNull()

        MusicSearchSections(
            topResult = top,
            songs = songs.take(20),
            videos = videos.take(10),
            albums = albums.take(10),
            artists = artists.take(10),
        )
    }


    suspend fun homeFeed(): List<YtTrack> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val kiosks = service.kioskList
        val kioskUrl = kiosks.getListLinkHandlerFactoryByType("Trending").fromId("Trending")
        val kiosk = kiosks.getExtractorByUrl(kioskUrl.url, null)
        kiosk.fetchPage()
        val items = KioskInfo.getInfo(service, kioskUrl.url).relatedItems
        items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(isVideo = true) }
    }


    suspend fun resolveAudioStream(url: String): String = withContext(Dispatchers.IO) {
        try {
            extractAudioOnce(url)
        } catch (first: Exception) {
            val msg = (first.message ?: "").lowercase()
            val isStale = "page" in msg && "reload" in msg ||
                    "could not parse" in msg ||
                    "signature" in msg ||
                    "decipher" in msg ||
                    "nsig" in msg
            if (!isStale) throw first
            try {
                NewPipe.init(
                    NewPipeDownloader.instance,
                    org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                    org.schabi.newpipe.extractor.localization.ContentCountry.DEFAULT,
                )
            } catch (_: Throwable) { }
            extractAudioOnce(url)
        }
    }

    private fun extractAudioOnce(url: String): String {
        val info = StreamInfo.getInfo(NewPipe.getService(0), url)
        if (info.audioStreams.isNullOrEmpty()) {
            error("YouTube returned no audio streams for this track. (PoToken/age-gate or region block)")
        }
        val m4a = info.audioStreams.filter { it.format?.suffix?.equals("m4a", true) == true }
        val pool = if (m4a.isNotEmpty()) m4a else info.audioStreams
        val best = pool.maxByOrNull { it.averageBitrate }
            ?: error("Could not pick an audio stream from ${info.audioStreams.size} candidates.")
        return best.content?.takeIf { it.isNotBlank() }
            ?: error("Selected audio stream had a blank URL. Try another track.")
    }

    private fun StreamInfoItem.toTrack(isVideo: Boolean): YtTrack? {
        val u = url ?: return null
        return YtTrack(
            title = name ?: "Untitled",
            uploader = uploaderName.orEmpty(),
            durationSec = duration,
            url = u,
            thumbnail = thumbnails?.lastOrNull()?.url?.hqYtThumb(720),
            isVideo = isVideo,
        )
    }

    private fun humanCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).removeSuffix(".0M") + "M"
        n >= 1_000 -> "%.1fK".format(n / 1_000.0).removeSuffix(".0K") + "K"
        else -> n.toString()
    }
}

