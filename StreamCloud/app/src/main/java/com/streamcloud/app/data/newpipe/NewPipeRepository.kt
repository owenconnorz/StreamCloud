package com.streamcloud.app.data.newpipe

import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.util.hqYtThumb
import com.streamcloud.app.data.ytmusic.YtMusicSearchRepository
import dev.maxrave.pipepipe.extractor.NewPipe as PipePipe
import dev.maxrave.pipepipe.extractor.ServiceList as PipePipeServiceList
import dev.maxrave.pipepipe.extractor.stream.StreamInfo as PipePipeStreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe as BravePipe
import org.schabi.newpipe.extractor.ServiceList as BravePipeServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo as BravePipeStreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.services.youtube.search.filter.YoutubeFilters
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

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

data class StreamMeta(
    val viewCount: Long,
    val likeCount: Long,
    val uploadDate: String?,
    val description: String,
    val uploaderAvatarUrl: String?,
    val uploaderSubscriberCount: Long,
)

data class MusicSearchSections(
    val topResult: YtTrack? = null,
    val songs: List<YtTrack> = emptyList(),
    val videos: List<YtTrack> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val artists: List<YtArtist> = emptyList(),
)

object NewPipeRepository {
    private const val TAG = "YouTubeExtractor"

    /**
     * A stream URL is only useful if it works from the same IPv4 network profile used by the
     * Media3 data source. This prevents passing an already-rejected Googlevideo URL to ExoPlayer.
     */
    private val streamHealthClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname)
                        .filterIsInstance<Inet4Address>()
                        .ifEmpty { Dns.SYSTEM.lookup(hostname) }
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val pipePipeInitLock = Any()

    data class ExtractedAudioStream(
        val url: String,
        val resolverLabel: String,
        /**
         * Extractor URLs intentionally use the anonymous Android playback profile. This prevents
         * MusicPlaybackService from attaching a browser cookie/session to a URL minted by a
         * separate extractor, while preserving the same profile in validation and Media3.
         */
        val userAgent: String = PipePipeDownloader.PLAYBACK_USER_AGENT,
    )

    data class ExtractedVideoStream(
        val url: String,
        val resolverLabel: String,
        val userAgent: String = PipePipeDownloader.PLAYBACK_USER_AGENT,
    )

    private data class AudioCandidate(
        val url: String,
        val container: String?,
        val averageBitrate: Int,
    )

    private data class VideoCandidate(
        val url: String,
        val container: String?,
        val height: Int,
    )

    // ── Suggestions ───────────────────────────────────────────────────────────────
    // YouTube Music InnerTube suggestions → NewPipe fallback

    suspend fun searchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val ytm = runCatching { YtMusicSearchRepository.suggestions(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        runCatching {
            BravePipeServiceList.YouTube.suggestionExtractor.suggestionList(query)
        }.getOrDefault(emptyList())
    }

    // ── Search ────────────────────────────────────────────────────────────────────
    // Primary: YouTube Music InnerTube API (same as SimpMusic)
    // Fallback: NewPipe YouTube search with music_* filter

    suspend fun searchSongs(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val ytm = runCatching { YtMusicSearchRepository.songs(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        searchTracksNewPipe(
            query,
            YoutubeFilters.ID_CF_MAIN_YOUTUBE_MUSIC_SONGS,
            isVideo = false,
        )
    }

    suspend fun searchVideos(query: String): List<YtTrack> = withContext(Dispatchers.IO) {
        val ytm = runCatching { YtMusicSearchRepository.videos(query) }.getOrDefault(emptyList())
        if (ytm.isNotEmpty()) return@withContext ytm
        searchTracksNewPipe(
            query,
            YoutubeFilters.ID_CF_MAIN_YOUTUBE_MUSIC_VIDEOS,
            isVideo = true,
        )
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

    private suspend fun searchTracksNewPipe(
        query: String,
        contentFilterId: Int,
        isVideo: Boolean,
    ): List<YtTrack> =
        withContext(Dispatchers.IO) {
            val service = BravePipeServiceList.YouTube
            val info = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(
                    query,
                    listOf(service.searchQHFactory.getFilterItem(contentFilterId)),
                    emptyList(),
                ),
            )
            info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(isVideo) }
        }

    private suspend fun searchAlbumsNewPipe(query: String): List<YtAlbum> =
        withContext(Dispatchers.IO) {
            val service = BravePipeServiceList.YouTube
            val info = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(
                    query,
                    listOf(
                        service.searchQHFactory.getFilterItem(
                            YoutubeFilters.ID_CF_MAIN_YOUTUBE_MUSIC_ALBUMS,
                        ),
                    ),
                    emptyList(),
                ),
            )
            info.relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { it.toAlbum() }
        }

    private suspend fun searchArtistsNewPipe(query: String): List<YtArtist> =
        withContext(Dispatchers.IO) {
            val service = BravePipeServiceList.YouTube
            val info = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(
                    query,
                    listOf(
                        service.searchQHFactory.getFilterItem(
                            YoutubeFilters.ID_CF_MAIN_YOUTUBE_MUSIC_ARTISTS,
                        ),
                    ),
                    emptyList(),
                ),
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
            val service = BravePipeServiceList.YouTube
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
            val service = BravePipeServiceList.YouTube
        val kiosks = service.kioskList
        val kioskUrl = kiosks.getListLinkHandlerFactoryByType("Trending").fromId("Trending")
        val kiosk = kiosks.getExtractorByUrl(kioskUrl.url, null)
        kiosk.fetchPage()
        val items = KioskInfo.getInfo(service, kioskUrl.url).relatedItems
        items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack(isVideo = true) }
    }

    // ── Playback ──────────────────────────────────────────────────────────────────

    /**
     * Resolves a playable audio URL through two independently maintained parsers. PipePipe is
     * preferred for music pages; BravePipe is intentionally retained as a separate fallback
     * because its signature and throttling handling can succeed when PipePipe cannot.
     */
    suspend fun resolveVerifiedAudioStream(url: String): ExtractedAudioStream? =
        withContext(Dispatchers.IO) {
            resolveWithPipePipe(url) ?: resolveWithBravePipe(url)
        }

    /**
     * Resolves a visual MP4 stream through the same maintained extractors used for audio. A
     * video-only DASH track is sufficient because Now Playing keeps this player muted and synced
     * to the primary audio player.
     */
    suspend fun resolveVerifiedVideoStream(url: String): ExtractedVideoStream? =
        withContext(Dispatchers.IO) {
            resolveVideoWithPipePipe(url) ?: resolveVideoWithBravePipe(url)
        }

    /** Maintains the legacy URL-only call surface for downloads, Sonos, and older call sites. */
    suspend fun resolveAudioStream(url: String): String =
        resolveVerifiedAudioStream(url)?.url
            ?: error("No verified YouTube audio stream was available for this track.")

    private fun resolveWithPipePipe(watchUrl: String): ExtractedAudioStream? = runCatching {
        synchronized(pipePipeInitLock) {
            PipePipeDownloader.instance.ytMusicCookie = NewPipeDownloader.instance.ytMusicCookie
            PipePipe.init(PipePipeDownloader.instance)
        }
        val info = PipePipeStreamInfo.getInfo(
            PipePipeServiceList.YouTube,
            musicWatchUrl(watchUrl),
        )
        selectVerifiedCandidate(
            "PIPEPIPE",
            info.audioStreams.orEmpty().mapNotNull { stream ->
                stream.content?.takeIf { it.isNotBlank() }?.let { streamUrl ->
                    AudioCandidate(
                        url = streamUrl,
                        container = stream.format?.suffix,
                        averageBitrate = stream.averageBitrate,
                    )
                }
            },
        )
    }.onFailure { error ->
        AppLogger.w(TAG, "PipePipe extraction failed: ${error.message}")
    }.getOrNull()

    private fun resolveWithBravePipe(watchUrl: String): ExtractedAudioStream? {
        fun extract(): ExtractedAudioStream? {
            val info = BravePipeStreamInfo.getInfo(
                BravePipe.getService(0),
                browserWatchUrl(watchUrl),
            )
            return selectVerifiedCandidate(
                "BRAVEPIPE",
                info.audioStreams.orEmpty().mapNotNull { stream ->
                    stream.content?.takeIf { it.isNotBlank() }?.let { streamUrl ->
                        AudioCandidate(
                            url = streamUrl,
                            container = stream.format?.suffix,
                            averageBitrate = stream.averageBitrate,
                        )
                    }
                },
            )
        }

        return try {
            extract()
        } catch (first: Exception) {
            if (!isRecoverableExtractorFailure(first)) {
                AppLogger.w(TAG, "BravePipe extraction failed: ${first.message}")
                return null
            }
            AppLogger.w(TAG, "BravePipe parser was stale; reinitializing once")
            runCatching {
                BravePipe.init(
                    NewPipeDownloader.instance,
                    org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                    org.schabi.newpipe.extractor.localization.ContentCountry.DEFAULT,
                )
            }
            runCatching { extract() }
                .onFailure { AppLogger.w(TAG, "BravePipe retry failed: ${it.message}") }
                .getOrNull()
        }
    }

    private fun resolveVideoWithPipePipe(watchUrl: String): ExtractedVideoStream? = runCatching {
        synchronized(pipePipeInitLock) {
            PipePipeDownloader.instance.ytMusicCookie = NewPipeDownloader.instance.ytMusicCookie
            PipePipe.init(PipePipeDownloader.instance)
        }
        val info = PipePipeStreamInfo.getInfo(
            PipePipeServiceList.YouTube,
            musicWatchUrl(watchUrl),
        )
        selectVerifiedVideoCandidate(
            "PIPEPIPE",
            (info.videoStreams.orEmpty() + info.videoOnlyStreams.orEmpty()).mapNotNull { stream ->
                stream.content?.takeIf { it.isNotBlank() }?.let { streamUrl ->
                    VideoCandidate(
                        url = streamUrl,
                        container = stream.format?.suffix,
                        height = stream.resolution
                            ?.substringBefore('p')
                            ?.toIntOrNull()
                            ?: 0,
                    )
                }
            },
        )
    }.onFailure { error ->
        AppLogger.w(TAG, "PipePipe video extraction failed: ${error.message}")
    }.getOrNull()

    private fun resolveVideoWithBravePipe(watchUrl: String): ExtractedVideoStream? {
        fun extract(): ExtractedVideoStream? {
            val info = BravePipeStreamInfo.getInfo(
                BravePipe.getService(0),
                browserWatchUrl(watchUrl),
            )
            return selectVerifiedVideoCandidate(
                "BRAVEPIPE",
                (info.videoStreams.orEmpty() + info.videoOnlyStreams.orEmpty()).mapNotNull { stream ->
                    stream.content?.takeIf { it.isNotBlank() }?.let { streamUrl ->
                        VideoCandidate(
                            url = streamUrl,
                            container = stream.format?.suffix,
                            height = stream.resolution
                                ?.substringBefore('p')
                                ?.toIntOrNull()
                                ?: 0,
                        )
                    }
                },
            )
        }

        return try {
            extract()
        } catch (first: Exception) {
            if (!isRecoverableExtractorFailure(first)) {
                AppLogger.w(TAG, "BravePipe video extraction failed: ${first.message}")
                return null
            }
            AppLogger.w(TAG, "BravePipe video parser was stale; reinitializing once")
            runCatching {
                BravePipe.init(
                    NewPipeDownloader.instance,
                    org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                    org.schabi.newpipe.extractor.localization.ContentCountry.DEFAULT,
                )
            }
            runCatching { extract() }
                .onFailure { AppLogger.w(TAG, "BravePipe video retry failed: ${it.message}") }
                .getOrNull()
        }
    }

    private fun selectVerifiedCandidate(
        resolverLabel: String,
        candidates: List<AudioCandidate>,
    ): ExtractedAudioStream? {
        if (candidates.isEmpty()) {
            AppLogger.w(TAG, "$resolverLabel returned no audio candidates")
            return null
        }
        val ranked = candidates
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<AudioCandidate> { it.container.equals("m4a", ignoreCase = true) }
                    .thenByDescending { it.averageBitrate },
            )
        for (candidate in ranked) {
            if (validateStreamUrl(candidate.url)) {
                AppLogger.i(
                    TAG,
                    "$resolverLabel selected ${candidate.container ?: "unknown"} " +
                        "${candidate.averageBitrate / 1000}kbps after range validation",
                )
                return ExtractedAudioStream(candidate.url, resolverLabel)
            }
        }
        AppLogger.w(TAG, "$resolverLabel returned ${ranked.size} audio URLs, but none passed validation")
        return null
    }

    private fun selectVerifiedVideoCandidate(
        resolverLabel: String,
        candidates: List<VideoCandidate>,
    ): ExtractedVideoStream? {
        if (candidates.isEmpty()) {
            AppLogger.w(TAG, "$resolverLabel returned no video candidates")
            return null
        }
        val mp4 = candidates.filter { it.container.equals("mp4", ignoreCase = true) }
        val compatible = mp4.ifEmpty { candidates }
        val compact = compatible.filter { it.height in 1..720 }
        val ranked = (compact.ifEmpty { compatible })
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<VideoCandidate> { it.container.equals("mp4", ignoreCase = true) }
                    .thenByDescending { it.height.coerceAtMost(720) }
                    .thenBy { it.height },
            )
        for (candidate in ranked) {
            if (validateStreamUrl(candidate.url)) {
                AppLogger.i(
                    TAG,
                    "$resolverLabel selected ${candidate.container ?: "unknown"} " +
                        "${candidate.height.takeIf { it > 0 }?.let { "${it}p" } ?: "video"} " +
                        "after range validation",
                )
                return ExtractedVideoStream(candidate.url, resolverLabel)
            }
        }
        AppLogger.w(TAG, "$resolverLabel returned ${ranked.size} video URLs, but none passed validation")
        return null
    }

    /**
     * Googlevideo can reject HEAD while accepting a normal Media3 range request. Validate with the
     * request shape the player will actually use instead of treating a syntactically valid URL as
     * playable.
     */
    private fun validateStreamUrl(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", PipePipeDownloader.PLAYBACK_USER_AGENT)
            .header("Range", "bytes=0-1023")
            .header("Accept-Encoding", "identity")
            .build()
        streamHealthClient.newCall(request).execute().use { response ->
            response.code in 200..299
        }
    }.getOrDefault(false)

    private fun isRecoverableExtractorFailure(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return ("page" in message && "reload" in message) ||
            "could not parse" in message ||
            "signature" in message ||
            "decipher" in message ||
            "nsig" in message
    }

    private fun musicWatchUrl(rawUrl: String): String =
        youtubeVideoId(rawUrl)?.let { "https://music.youtube.com/watch?v=$it" } ?: rawUrl

    private fun browserWatchUrl(rawUrl: String): String =
        youtubeVideoId(rawUrl)?.let { "https://www.youtube.com/watch?v=$it" } ?: rawUrl

    private fun youtubeVideoId(rawUrl: String): String? =
        rawUrl.substringAfter("v=", "")
            .substringBefore('&')
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }

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

    private val streamMetaCache: MutableMap<String, StreamMeta> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, StreamMeta>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, StreamMeta>) = size > 30
            },
        )

    /**
     * Fetches lightweight metadata (views, likes, upload date, description, uploader avatar)
     * for a YouTube video. Results are cached in memory by videoId.
     */
    suspend fun fetchStreamMeta(videoId: String): StreamMeta = withContext(Dispatchers.IO) {
        streamMetaCache[videoId]?.let { return@withContext it }
        val url = "https://www.youtube.com/watch?v=$videoId"
        val info = BravePipeStreamInfo.getInfo(BravePipe.getService(0), url)
        StreamMeta(
            viewCount = info.viewCount.coerceAtLeast(0L),
            likeCount = info.likeCount.coerceAtLeast(0L),
            uploadDate = info.textualUploadDate,
            description = info.description?.content.orEmpty(),
            uploaderAvatarUrl = (info.uploaderAvatars?.maxByOrNull { it.width }?.url
                ?: info.uploaderAvatars?.firstOrNull()?.url)
                ?.replace(Regex("=s\\d+"), "=s400"),
            uploaderSubscriberCount = info.uploaderSubscriberCount.coerceAtLeast(0L),
        ).also { streamMetaCache[videoId] = it }
    }
}
