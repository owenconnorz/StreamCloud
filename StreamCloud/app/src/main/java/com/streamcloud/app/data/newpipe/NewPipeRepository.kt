package com.streamcloud.app.data.newpipe

import com.streamcloud.app.data.util.hqYtThumb
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    val viewCount: Long = 0L,
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

    // ── Suggestions ───────────────────────────────────────────────────────────────
    // YouTube Music InnerTube suggestions → NewPipe fallback

    suspend fun searchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val ytm = runCatching { YtMusicSearchRepository.suggestions(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        runCatching {
            ServiceList.YouTube.suggestionExtractor.suggestionList(query)
        }.getOrDefault(emptyList())
    }

    // ── Search ────────────────────────────────────────────────────────────────────
    // Primary: YouTube Music InnerTube API (same as SimpMusic)
    // Fallback: NewPipe YouTube search with music_* filter

    suspend fun searchSongs(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val ytm = runCatching { YtMusicSearchRepository.songs(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        searchTracksNewPipe(query, "music_songs", isVideo = false)
    }

    suspend fun searchVideos(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val ytm = runCatching { YtMusicSearchRepository.videos(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        searchTracksNewPipe(query, "music_videos", isVideo = true)
    }

    suspend fun searchAlbums(query: String): List<YtAlbum> = withContext(Dispatchers.IO) {
        val ytm = runCatching { YtMusicSearchRepository.albums(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        searchAlbumsNewPipe(query)
    }

    suspend fun searchArtists(query: String): List<YtArtist> = withContext(Dispatchers.IO) {
        val ytm = runCatching { YtMusicSearchRepository.artists(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        searchArtistsNewPipe(query)
    }

    // ── Fallback NewPipe search ───────────────────────────────────────────────────

    private suspend fun searchTracksNewPipe(query: String, filter: String, isVideo: Boolean): List<YtTrack> =
        withContext(Dispatchers.IO) {
            val service = ServiceList.YouTube
            val info = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(query, listOf(filter), ""),
            )
            info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(isVideo) }
        }

    private suspend fun searchAlbumsNewPipe(query: String): List<YtAlbum> =
        withContext(Dispatchers.IO) {
            val service = ServiceList.YouTube
            val info = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(query, listOf("music_albums"), ""),
            )
            info.relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toAlbum() }
        }

    private suspend fun searchArtistsNewPipe(query: String): List<YtArtist> =
        withContext(Dispatchers.IO) {
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

    // ── Artist page ───────────────────────────────────────────────────────────────

    data class ArtistPage(
        val name: String,
        val avatar: String?,
        val banner: String?,
        val description: String,
        val subscriberLabel: String?,
        val viewCount: Long = 0L,
        val topTracks: List<YtTrack>,
        val albums: List<YtAlbum>,
        val singles: List<YtAlbum> = emptyList(),
        val videos: List<YtTrack> = emptyList(),
        val featuredOn: List<YtAlbum> = emptyList(),
        val relatedArtists: List<YtArtist> = emptyList(),
    )

    suspend fun loadArtist(channelUrl: String): ArtistPage? = withContext(Dispatchers.IO) {
        // Try YouTube Music InnerTube browse first — gives all sections (Songs, Albums, Singles,
        // Videos, Featured on, Related artists) exactly like SimpMusic does.
        val channelId = channelUrl.substringAfterLast("/")
        if (channelId.isNotBlank()) {
            val ytmPage = runCatching {
                com.streamcloud.app.data.ytmusic.YtMusicArtistRepository.load(channelId)
            }.getOrNull()
            if (ytmPage != null) return@withContext ytmPage
        }

        // Fallback: NewPipe ChannelInfo + parallel tab loading + search fallbacks
        val service = ServiceList.YouTube
        val info = runCatching {
            org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(service, channelUrl)
        }.getOrNull() ?: return@withContext null

        val artistName = (info.name ?: "").removeSuffix(" - Topic").trim()

        var tracks: List<YtTrack> = emptyList()
        var albums: List<YtAlbum> = emptyList()
        var singles: List<YtAlbum> = emptyList()
        var featuredOn: List<YtAlbum> = emptyList()
        var relatedArtists: List<YtArtist> = emptyList()

        coroutineScope {
            val tracksJob = async {
                val tabTracks = runCatching {
                    val t = info.tabs.getOrNull(0) ?: return@runCatching emptyList<YtTrack>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(service, t)
                    tabInfo.relatedItems.filterIsInstance<StreamInfoItem>()
                        .mapNotNull { it.toTrack(isVideo = false) }.take(20)
                }.getOrDefault(emptyList())
                if (tabTracks.isNotEmpty()) tabTracks
                else runCatching { searchSongs(artistName) }.getOrDefault(emptyList()).take(10)
            }
            val albumsJob = async {
                val tabAlbums = runCatching {
                    val t = info.tabs.getOrNull(1) ?: return@runCatching emptyList<YtAlbum>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(service, t)
                    tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>()
                        .mapNotNull { it.toAlbum() }.take(10)
                }.getOrDefault(emptyList())
                if (tabAlbums.isNotEmpty()) tabAlbums
                else runCatching { searchAlbums(artistName) }.getOrDefault(emptyList()).take(8)
            }
            val singlesJob = async {
                runCatching {
                    val t = info.tabs.getOrNull(2) ?: return@runCatching emptyList<YtAlbum>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(service, t)
                    tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>()
                        .mapNotNull { it.toAlbum() }.take(10)
                }.getOrDefault(emptyList())
            }
            val featuredJob = async {
                runCatching {
                    val t = info.tabs.getOrNull(3) ?: return@runCatching emptyList<YtAlbum>()
                    val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(service, t)
                    tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>()
                        .mapNotNull { it.toAlbum() }.take(10)
                }.getOrDefault(emptyList())
            }
            val relatedJob = async {
                runCatching {
                    searchArtists(artistName)
                        .filter { !it.name.equals(artistName, ignoreCase = true) }
                        .take(6)
                }.getOrDefault(emptyList())
            }
            tracks = tracksJob.await()
            albums = albumsJob.await()
            singles = singlesJob.await().let { s ->
                if (s.map { it.url }.toSet() == albums.map { it.url }.toSet()) emptyList() else s
            }
            featuredOn = featuredJob.await()
            relatedArtists = relatedJob.await()
        }

        ArtistPage(
            name = artistName,
            avatar = info.avatars?.lastOrNull()?.url,
            banner = info.banners?.lastOrNull()?.url,
            description = info.description.orEmpty(),
            subscriberLabel = info.subscriberCount.takeIf { it >= 0 }
                ?.let { humanCount(it) + " subscribers" },
            viewCount = 0L,
            topTracks = tracks,
            albums = albums,
            singles = singles,
            videos = tracks.filter { it.viewCount > 0 }.ifEmpty { tracks }.take(6),
            featuredOn = featuredOn,
            relatedArtists = relatedArtists,
        )
    }

    // ── Aggregate search ─────────────────────────────────────────────────────────

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

    // ── Home feed ─────────────────────────────────────────────────────────────────

    suspend fun homeFeed(): List<YtTrack> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val kiosks = service.kioskList
        val kioskUrl = kiosks.getListLinkHandlerFactoryByType("Trending").fromId("Trending")
        val kiosk = kiosks.getExtractorByUrl(kioskUrl.url, null)
        kiosk.fetchPage()
        val items = KioskInfo.getInfo(service, kioskUrl.url).relatedItems
        items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(isVideo = true) }
    }

    // ── Playback ──────────────────────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun StreamInfoItem.toTrack(isVideo: Boolean): YtTrack? {
        val u = url ?: return null
        return YtTrack(
            title = name ?: "Untitled",
            uploader = uploaderName.orEmpty(),
            durationSec = duration,
            url = u,
            thumbnail = thumbnails?.lastOrNull()?.url?.hqYtThumb(720),
            isVideo = isVideo,
            viewCount = viewCount.takeIf { it >= 0 } ?: 0L,
        )
    }

    private fun PlaylistInfoItem.toAlbum(): YtAlbum? {
        val u = url ?: return null
        return YtAlbum(
            title = name ?: "Untitled",
            artist = uploaderName.orEmpty(),
            url = u,
            thumbnail = thumbnails?.lastOrNull()?.url?.hqYtThumb(720),
        )
    }

    private fun humanCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1f".format(n / 1_000_000.0).trimEnd('0').trimEnd('.') + "M"
        n >= 1_000 -> "%.1f".format(n / 1_000.0).trimEnd('0').trimEnd('.') + "K"
        else -> n.toString()
    }
}
