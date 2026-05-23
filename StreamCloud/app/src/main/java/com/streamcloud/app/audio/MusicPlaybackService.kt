package com.streamcloud.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.streamcloud.app.MainActivity
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.widget.MusicWidgetProvider
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.downloads.DownloadCaches
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackDao
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import com.streamcloud.app.data.ytmusic.StreamUrlCache
import com.streamcloud.app.data.ytmusic.YtMusicLibrary
import com.streamcloud.app.data.ytmusic.YtMusicLibraryRepository
import com.streamcloud.app.data.ytmusic.YtMusicHomeRepository
import com.streamcloud.app.data.ytmusic.YtMusicHomeFeed
import com.streamcloud.app.data.ytmusic.HomeSection
import com.streamcloud.app.data.ytmusic.YtmPlaylist
import com.streamcloud.app.data.ytmusic.YtmSong
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var audioFx: AudioFx? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var playerCache: SimpleCache
    private lateinit var downloadCache: SimpleCache


    @Volatile private var ytLibrary: YtMusicLibrary = YtMusicLibrary()
    @Volatile private var ytHomeFeed: YtMusicHomeFeed = YtMusicHomeFeed()


    @Volatile private var ytMusicCookieForStream: String = ""
    private val nThrottledVideoIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())





    private val sl by lazy { ServiceLocator.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()

        playerCache = DownloadCaches.playerCache(this)
        downloadCache = DownloadCaches.downloadCache(this)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(buildDataSourceFactory())

        val musicAudioAttrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)


            .setAudioAttributes(musicAudioAttrs,  true)

            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = false
            }

        // When casting to Sonos, ExoPlayer must stay paused so audio only plays from the
        // Sonos speaker (not the phone). If something triggers player.play() (e.g. the
        // media notification Play button), immediately re-pause and resume Sonos instead.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && SonosRepository.castState.value is SonosRepository.CastState.Casting) {
                    player.pause()
                    SonosRepository.resume()
                }
            }
        })

        // Update the home-screen widget whenever the current track changes.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val title      = mediaItem?.mediaMetadata?.title?.toString()  ?: return
                val artist     = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val artworkUrl = mediaItem?.mediaMetadata?.artworkUri?.toString() ?: ""
                ioScope.launch {
                    val recentUrls = runCatching {
                        com.streamcloud.app.data.library.LibraryDb
                            .get(this@MusicPlaybackService).tracks().recent().first()
                            .take(5).mapNotNull { it.thumbnail }
                    }.getOrElse { emptyList() }
                    MusicWidgetProvider.updateNowPlaying(
                        applicationContext, title, artist, artworkUrl, recentUrls,
                    )
                }
            }
        })

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val is403 = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    generateSequence<Throwable>(error) { it.cause }
                        .any { "403" in (it.message ?: "") }
                if (!is403) return
                val videoId = player.currentMediaItem?.mediaId
                    ?.substringAfter("v=", "")?.substringBefore("&")?.ifBlank { null } ?: return
                if (nThrottledVideoIds.add(videoId)) {
                    StreamUrlCache.remove(videoId)
                    AppLogger.w(TAG, "403 for $videoId — n-throttled, retrying via NewPipe")
                    player.prepare()
                    player.play()
                }
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivityIntent)
            .build()

        audioFx = AudioFx(applicationContext, player.audioSessionId).also { it.start() }



        YtPlayerUtils.appContext = applicationContext
        ioScope.launch { YtPlayerUtils.warmUp() }




        ioScope.launch {
            sl.settings.ytMusicCookie.collect { cookie ->
                ytMusicCookieForStream = cookie
                YtPlayerUtils.ytMusicCookie = cookie
                if (cookie.isNotBlank()) {
                    ytLibrary  = YtMusicLibraryRepository.sync(cookie)
                    ytHomeFeed = YtMusicHomeRepository.load(cookie)
                }
            }
        }




        ioScope.launch {
            sl.settings.contentLanguage.collect { YtPlayerUtils.contentLanguage = it }
        }
        ioScope.launch {
            sl.settings.contentCountry.collect { YtPlayerUtils.contentCountry = it }
        }
    }



    private fun buildDataSourceFactory(): ResolvingDataSource.Factory {









        val streamOkHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val host = chain.request().url.host
                val reqBuilder = chain.request().newBuilder()
                val cookie = ytMusicCookieForStream
                if (cookie.isNotBlank() && !host.endsWith("googlevideo.com"))
                    reqBuilder.header("Cookie", cookie)
                if (host.endsWith("googlevideo.com") && chain.request().header("Range") == null)
                    reqBuilder.header("Range", "bytes=0-")
                chain.proceed(reqBuilder.build())
            }
            .build()

        val httpFactory = OkHttpDataSource.Factory(streamOkHttp)

        val chainedCacheFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(httpFactory),
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ResolvingDataSource.Factory(chainedCacheFactory) { dataSpec ->
            val cacheKey = dataSpec.key ?: dataSpec.uri.toString()


            val probeLen = if (dataSpec.length >= 0) dataSpec.length else 1L


            if (downloadCache.isCached(cacheKey, dataSpec.position, probeLen) ||
                playerCache.isCached(cacheKey, dataSpec.position, probeLen)
            ) return@Factory dataSpec

            val watchUrl = if (cacheKey.startsWith("http")) cacheKey
                           else "https://music.youtube.com/watch?v=$cacheKey"


            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            val localPath = runBlocking(Dispatchers.IO) { dao.byUrl(watchUrl)?.localPath }
            if (localPath != null && File(localPath).exists()) {
                return@Factory dataSpec.withUri(localPath.toUri())
            }

            val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")
            val (streamUrl, userAgent) = resolveStreamUrl(videoId, watchUrl)




            dataSpec.buildUpon()
                .setUri(streamUrl.toUri())
                .setHttpRequestHeaders(
                    dataSpec.httpRequestHeaders + mapOf("User-Agent" to userAgent)
                )
                .build()
        }
    }


    private fun resolveStreamUrl(videoId: String, watchUrl: String): Pair<String, String> {
        val now = System.currentTimeMillis()


        StreamUrlCache.getEntry(videoId)?.let { entry ->
            val ttl = StreamUrlCache.ttlSeconds(videoId) ?: 0
            Log.d(TAG, "StreamUrlCache hit for $videoId (ttl=${ttl}s)")
            return Pair(entry.url, entry.userAgent)
        }


        if (videoId !in nThrottledVideoIds) {
            val innertubeResult = runBlocking(Dispatchers.IO) {
                runCatching { YtPlayerUtils.resolveAudioFormatInfo(videoId) }
            }
            val info = innertubeResult.getOrNull()
            if (innertubeResult.isFailure) {
                AppLogger.w(TAG, "Innertube failed for $videoId", innertubeResult.exceptionOrNull())
            }
            if (info != null) {
                val expiryMs = now + (info.expiresInSeconds - 300).coerceAtLeast(60) * 1_000L
                StreamUrlCache.put(videoId, info.url, info.userAgent, expiryMs)
                AppLogger.i(TAG, "Innertube resolved $videoId itag=${info.itag} expires=${info.expiresInSeconds}s")
                Log.d(TAG, "Innertube resolved $videoId itag=${info.itag} ua=${info.userAgent.take(40)}")
                return Pair(info.url, info.userAgent)
            }
        } else {
            AppLogger.w(TAG, "Skipping Innertube for n-throttled $videoId — going straight to NewPipe")
        }

        val npUserAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
        AppLogger.w(TAG, "$videoId — falling back to NewPipe")
        Log.d(TAG, "Innertube failed for $videoId — falling back to NewPipe")
        val npResult = runBlocking(Dispatchers.IO) {
            runCatching { NewPipeRepository.resolveAudioStream(watchUrl) }
        }
        val npUrl = npResult.getOrNull()
            ?: run {
                val err = "Both Innertube and NewPipe failed to resolve stream for $videoId"
                AppLogger.e(TAG, err, npResult.exceptionOrNull())
                error(err)
            }


        StreamUrlCache.put(videoId, npUrl, npUserAgent, now + 3_600_000L)
        Log.d(TAG, "NewPipe resolved $videoId (cached 1 h)")
        return Pair(npUrl, npUserAgent)
    }

    // Keep the media notification (and foreground service) alive while casting to Sonos.
    // By default Media3 stops the foreground service when player.isPlaying == false,
    // which is exactly the state ExoPlayer is in during casting.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val casting = SonosRepository.castState.value is SonosRepository.CastState.Casting
        super.onUpdateNotification(session, startInForegroundRequired || casting)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player ?: return
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        ioScope.cancel()
        audioFx?.release(); audioFx = null
        session?.run { player.release(); release(); session = null }
        super.onDestroy()
    }



    private inner class LibraryCallback : MediaLibrarySession.Callback {

        // Explicitly allow all controllers (Android Auto, Google Assistant, widgets).
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            // Ensure search commands are available so voice/Gemini queries reach onSearch()
            val commands = result.availableSessionCommands.buildUpon()
                .add(androidx.media3.session.SessionCommand(
                    androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
                .build()
            return MediaSession.ConnectionResult.accept(commands, result.availablePlayerCommands)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Android Auto sends isRecent=true when the car starts, expecting a root
            // whose children are immediately playable (recently played tracks).
            // Returning the full browse tree here causes AA to show "cannot connect".
            val root = if (params?.isRecent == true) {
                playlist(RECENT_ID, "Recently played")
            } else {
                buildRoot()
            }
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = staticBrowsable(mediaId)
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val fut = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            ioScope.launch {
                val children: List<MediaItem> = when {
                    parentId == ROOT_ID     -> rootChildren()
                    parentId == HOME_ID     -> homeChildren()
                    parentId == LIBRARY_ID  -> libraryChildren()
                    parentId == RECENT_ID   -> roomTracks { it.recent().first() }
                    parentId == ON_REPEAT_ID -> roomTracks { it.mostPlayed().first() }
                    parentId == LIKED_ID    -> likedChildren()
                    parentId == DOWNLOADED_ID -> roomTracks { it.downloaded().first() }
                    parentId == ARTISTS_ID -> artistsChildren()
                    parentId.startsWith(ARTIST_PREFIX) ->
                        artistTracks(parentId.removePrefix(ARTIST_PREFIX))
                    parentId.startsWith(YT_HOME_SECTION_PREFIX) ->
                        ytHomeSectionItems(parentId.removePrefix(YT_HOME_SECTION_PREFIX))
                    parentId.startsWith(YT_PLAYLIST_PREFIX) -> ytPlaylistTracks(
                        parentId.removePrefix(YT_PLAYLIST_PREFIX),
                    )
                    else -> emptyList()
                }
                fut.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }
            return fut
        }


        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::attachUri).toMutableList())


        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val fut = SettableFuture.create<LibraryResult<Void>>()
            ioScope.launch {
                runCatching { NewPipeRepository.searchSongs(query) }
                    .onSuccess { tracks ->
                        val items = ImmutableList.copyOf(
                            tracks.map { t ->
                                val videoId = t.url.substringAfter("v=").substringBefore("&")
                                ytmSong(
                                    videoId = videoId,
                                    title = t.title,
                                    artist = t.uploader,
                                    album = null,
                                    thumbnail = t.thumbnail,
                                    watchUrl = t.url,
                                )
                            },
                        )
                        session.notifySearchResultChanged(browser, query, items.size, params)
                    }
                fut.set(LibraryResult.ofVoid())
            }
            return fut
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val fut = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            ioScope.launch {
                val results = runCatching { NewPipeRepository.searchSongs(query) }.getOrElse { emptyList() }
                val items = ImmutableList.copyOf(
                    results.map { t ->
                        val videoId = t.url.substringAfter("v=").substringBefore("&")
                        ytmSong(videoId, t.title, t.uploader, null, t.thumbnail, t.url)
                    },
                )
                fut.set(LibraryResult.ofItemList(items, params))
            }
            return fut
        }


        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val fut = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            ioScope.launch {
                val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
                val recent = runCatching { dao.recent().first() }.getOrElse { emptyList() }
                val items = recent.map(::trackEntityItem)
                fut.set(MediaSession.MediaItemsWithStartPosition(items, 0, 0))
            }
            return fut
        }
    }



    private fun buildRoot(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("StreamCloud")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(android.os.Bundle().apply {
                    // Content style: grid for browsable items (playlists), list for songs
                    putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                    putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
                    // Advertise voice/Gemini search support to Android Auto
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                })
                .build(),
        )
        .build()

    private fun rootChildren(): List<MediaItem> = listOf(
        tab(HOME_ID,    "Home",         browsableHint = 2), // grid — section tiles have artwork
        tab(RECENT_ID,  "Recents",      browsableHint = 1), // list — track rows
        tab(LIBRARY_ID, "Your Library", browsableHint = 1), // list — sub-folders have no artwork
    )

    /**
     * Home tab content: up to 20 recently played tracks with artwork so Android Auto
     * shows them immediately as a grid — no sub-folder drilling required.
     * Falls back to folder links when there is no listen history yet.
     */
    /**
     * Home tab — mirrors the in-app Music home page.
     *
     * Priority 1: YT Music home feed sections (Listen again, Forgotten favorites, etc.)
     *   shown as browsable grid tiles with the first item's artwork.
     * Priority 2: YT library (liked songs + playlists) when the home feed is unavailable.
     * Priority 3: Local Room DB recent tracks as a last-resort offline fallback.
     */
    private suspend fun homeChildren(): List<MediaItem> {

        // ── 1. YT Music home feed sections ──────────────────────────────────
        val feedSections = ytHomeFeed.sections.filter { section ->
            when (section) {
                is HomeSection.PlaylistRail -> section.items.isNotEmpty()
                is HomeSection.SongRail     -> section.items.isNotEmpty()
                else                        -> false
            }
        }
        if (feedSections.isNotEmpty()) {
            return feedSections.map { section ->
                val thumb = when (section) {
                    is HomeSection.PlaylistRail -> section.items.firstOrNull()?.thumbnail
                    is HomeSection.SongRail     -> section.items.firstOrNull()?.thumbnail
                    else                        -> null
                }
                sectionFolder("$YT_HOME_SECTION_PREFIX${section.title}", section.title, thumb)
            }
        }

        // ── 2. YT library fallback (cookie set but home feed failed) ────────
        val ytSongs  = ytLibrary.likedSongs.take(10)
        val ytPlists = ytLibrary.playlists.filter { !it.isAlbum }.take(8)
        if (ytSongs.isNotEmpty() || ytPlists.isNotEmpty()) {
            return ytSongs.map(::ytmSongItem) +
                ytPlists.map { pl -> ytPlaylistBrowsable("$YT_PLAYLIST_PREFIX${pl.id}", pl) }
        }

        // ── 3. Local recent tracks fallback ─────────────────────────────────
        val recent = runCatching {
            LibraryDb.get(this@MusicPlaybackService).tracks().recent().first()
        }.getOrElse { emptyList() }.take(20)

        if (recent.isEmpty()) return listOf(
            playlist(RECENT_ID,    "Recently played"),
            playlist(ON_REPEAT_ID, "On repeat"),
        )
        return recent.map(::trackEntityItem)
    }

    /** Expands a YT Music home-feed section into its playable/browsable children. */
    private fun ytHomeSectionItems(sectionTitle: String): List<MediaItem> {
        val section = ytHomeFeed.sections.find { it.title == sectionTitle }
        return when (section) {
            is HomeSection.PlaylistRail ->
                section.items.map { pl -> ytPlaylistBrowsable("$YT_PLAYLIST_PREFIX${pl.id}", pl) }
            is HomeSection.SongRail -> section.items.map(::ytmSongItem)
            else                    -> emptyList()
        }
    }

    /** Browsable grid tile for a home-feed section (e.g. "Listen again"). */
    private fun sectionFolder(id: String, title: String, thumbnail: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(thumbnail?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()


    private fun libraryChildren(): List<MediaItem> {
        val fixed = listOf(
            playlist(LIKED_ID,      "Liked Music"),
            playlist(DOWNLOADED_ID, "Downloads"),
            playlist(ON_REPEAT_ID,  "On repeat"),
            folder(ARTISTS_ID,      "Artists"),
        )
        val ytPlaylists = ytLibrary.playlists
            .filter { !it.isAlbum }
            .map { pl ->
                MediaItem.Builder()
                    .setMediaId("$YT_PLAYLIST_PREFIX${pl.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(pl.title)
                            .setSubtitle(pl.subtitle)
                            .setArtworkUri(pl.thumbnail?.let(Uri::parse))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                            .build(),
                    )
                    .build()
            }
        return fixed + ytPlaylists
    }


    private suspend fun likedChildren(): List<MediaItem> {
        val ytmLiked = ytLibrary.likedSongs
        if (ytmLiked.isNotEmpty()) return ytmLiked.map(::ytmSongItem)
        val local = runCatching {
            LibraryDb.get(this@MusicPlaybackService).tracks().liked().first()
        }.getOrElse { emptyList() }
        return local.map(::trackEntityItem)
    }


    private suspend fun ytPlaylistTracks(playlistId: String): List<MediaItem> {
        return try {
            val cookie = sl.settings.ytMusicCookie.first()
            if (cookie.isBlank()) return emptyList()
            YtMusicLibraryRepository.playlistTracks(cookie, playlistId).map(::ytmSongItem)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Deduplicated artist list built from recent playback history. */
    private suspend fun artistsChildren(): List<MediaItem> {
        val recent = runCatching {
            LibraryDb.get(this@MusicPlaybackService).tracks().recent().first()
        }.getOrElse { emptyList() }
        val seen = mutableSetOf<String>()
        return recent
            .filter { it.artist.isNotBlank() && seen.add(it.artist) }
            .take(30)
            .map { track ->
                MediaItem.Builder()
                    .setMediaId("$ARTIST_PREFIX\${track.artist}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.artist)
                            .setArtworkUri(track.thumbnail?.let(Uri::parse))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                            .build(),
                    )
                    .build()
            }
    }

    /** All recent tracks by a specific artist name. */
    private suspend fun artistTracks(artistName: String): List<MediaItem> {
        val recent = runCatching {
            LibraryDb.get(this@MusicPlaybackService).tracks().recent().first()
        }.getOrElse { emptyList() }
        return recent.filter { it.artist == artistName }.map(::trackEntityItem)
    }

    private suspend fun roomTracks(query: suspend (dao: com.streamcloud.app.data.library.TrackDao) -> List<TrackEntity>): List<MediaItem> =
        runCatching {
            val dao = LibraryDb.get(this).tracks()
            query(dao).map(::trackEntityItem)
        }.getOrElse { emptyList() }



    private fun trackEntityItem(t: TrackEntity): MediaItem = MediaItem.Builder()
        .setMediaId(t.url)
        .setUri(t.url)
        .setCustomCacheKey(t.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setArtworkUri(t.thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun ytmSongItem(s: YtmSong): MediaItem {
        val url = "https://music.youtube.com/watch?v=${s.videoId}"
        return ytmSong(s.videoId, s.title, s.artist, s.album, s.thumbnail, url)
    }

    private fun ytmSong(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        thumbnail: String?,
        watchUrl: String,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(watchUrl)
        .setUri(watchUrl)
        .setCustomCacheKey(watchUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putString("videoId", videoId)
                    putString("watchUrl", watchUrl)
                })
                .build(),
        )
        .build()

    /**
     * Root-level tab item. Sets per-item content style hints so Android Auto
     * renders children as a grid (browsable) or list (playable) automatically.
     */
    private fun tab(
        id: String,
        title: String,
        browsableHint: Int = 2,
        playableHint: Int  = 1,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle().apply {
                    putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
                    putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",  playableHint)
                })
                .build(),
        )
        .build()

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun playlist(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    private fun staticBrowsable(id: String): MediaItem? = when {
        id == ROOT_ID      -> buildRoot()
        id == HOME_ID      -> folder(HOME_ID, "Home")
        id == LIBRARY_ID   -> folder(LIBRARY_ID, "Your Library")
        id == RECENT_ID    -> playlist(RECENT_ID, "Recently played")
        id == ON_REPEAT_ID -> playlist(ON_REPEAT_ID, "On repeat")
        id == LIKED_ID     -> playlist(LIKED_ID, "Liked Music")
        id == DOWNLOADED_ID -> playlist(DOWNLOADED_ID, "Downloads")
        id == ARTISTS_ID    -> folder(ARTISTS_ID, "Artists")
        id.startsWith(YT_HOME_SECTION_PREFIX) -> {
            val ttl = id.removePrefix(YT_HOME_SECTION_PREFIX)
            val thb = ytHomeFeed.sections.find { it.title == ttl }
                ?.let { s -> when (s) {
                    is HomeSection.PlaylistRail -> s.items.firstOrNull()?.thumbnail
                    is HomeSection.SongRail     -> s.items.firstOrNull()?.thumbnail
                    else                        -> null
                }}
            sectionFolder(id, ttl, thb)
        }
        id.startsWith(ARTIST_PREFIX) ->
            MediaItem.Builder().setMediaId(id).setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(id.removePrefix(ARTIST_PREFIX))
                    .setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST).build()
            ).build()
        id.startsWith(YT_PLAYLIST_PREFIX) -> {
            val plId = id.removePrefix(YT_PLAYLIST_PREFIX)
            val pl = ytLibrary.playlists.find { it.id == plId }
            if (pl != null) ytPlaylistBrowsable(id, pl) else null
        }
        else -> null
    }

    private fun ytPlaylistBrowsable(mediaId: String, pl: YtmPlaylist): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(pl.title)
                    .setSubtitle(pl.subtitle)
                    .setArtworkUri(pl.thumbnail?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            )
            .build()

    private fun attachUri(item: MediaItem): MediaItem {
        if (item.localConfiguration?.uri != null) return item
        val id = item.mediaId.ifBlank { return item }
        return item.buildUpon().setUri(id).setCustomCacheKey(id).build()
    }

    companion object {
        private const val TAG    = "MusicPlaybackService"
        const val ROOT_ID        = "streamcloud_root"
        const val HOME_ID        = "streamcloud_home"
        const val LIBRARY_ID     = "streamcloud_library"
        const val RECENT_ID      = "streamcloud_recent"
        const val ON_REPEAT_ID   = "streamcloud_on_repeat"
        const val LIKED_ID       = "streamcloud_liked"
        const val DOWNLOADED_ID  = "streamcloud_downloaded"


        const val YT_PLAYLIST_PREFIX    = "ytpl_"
        const val YT_HOME_SECTION_PREFIX = "yths_"
        const val ARTISTS_ID     = "streamcloud_artists"
        const val ARTIST_PREFIX  = "sc_artist_"
    }
}
