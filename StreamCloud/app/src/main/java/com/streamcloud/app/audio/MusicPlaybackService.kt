package com.streamcloud.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.streamcloud.app.R
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

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var audioFx: AudioFx? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var playerCache: SimpleCache
    private lateinit var downloadCache: SimpleCache


    @Volatile private var ytLibrary: YtMusicLibrary = YtMusicLibrary()


    @Volatile private var ytMusicCookieForStream: String = ""
    @Volatile private var isCurrentLiked: Boolean = false





    private val sl by lazy { ServiceLocator.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(MusicNotificationProvider(this))

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
            .setAudioAttributes(musicAudioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = false
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        AppLogger.e(TAG, "ExoPlayer error code=${error.errorCode} msg=${error.message}", error.cause)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        val label = when (state) {
                            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                            androidx.media3.common.Player.STATE_READY -> "READY"
                            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($state)"
                        }
                        AppLogger.i(TAG, "playback state → $label")
                    }
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        refreshLikedState()
                    }
                    override fun onRepeatModeChanged(repeatMode: Int) {
                        session?.setCustomLayout(buildCustomLayout())
                    }
                })
            }

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
                    ytLibrary = YtMusicLibraryRepository.sync(cookie)
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









        // Force IPv4-only DNS for CDN requests.
        //
        // YouTube's player API embeds the source IP of the player request into the CDN URL
        // as `ip=<address>`.  If the player API goes via IPv4 (e.g. 82.132.x.x) but the
        // ExoPlayer OkHttp client connects to googlevideo.com via IPv6 (OkHttp's default
        // "Happy Eyeballs" prefers IPv6 when available), the CDN sees a different source IP
        // and returns HTTP 403 with an *empty body* — the exact symptom we observed.
        //
        // Fix: filter DNS results to IPv4 addresses only so the CDN request IP always
        // matches the `ip=` embedded in the URL.  Fall back to any address if no IPv4
        // record is available (shouldn't happen for googlevideo.com, but safe fallback).
        val ipv4OnlyDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname)
                    .filter { it is java.net.Inet4Address }
                    .ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
        }

        val streamOkHttp = OkHttpClient.Builder()
            .dns(ipv4OnlyDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val cookie = ytMusicCookieForStream
                val host = req.url.host
                val builder = req.newBuilder()

                val hasPot = req.url.queryParameter("pot") != null

                if (cookie.isNotBlank()) {
                    when {
                        // music.youtube.com API requests — always send cookie + browser headers.
                        host.endsWith("music.youtube.com") -> {
                            builder.header("Cookie", cookie)
                                   .header("Origin", "https://music.youtube.com")
                                   .header("Referer", "https://music.youtube.com/")
                        }
                        // googlevideo.com CDN — send Cookie for ALL requests, not just pot= ones.
                        //
                        // Previously we checked `pot != null`, but CDN redirects (alr=yes) produce
                        // a redirect-target URL that no longer contains pot=, so the old condition
                        // silently dropped Cookie on the final hop — causing a 403.
                        //
                        // Sending Cookie to unauthenticated (ANDROID_VR/TESTSUITE) CDN URLs is
                        // harmless: the CDN ignores extra cookies for anonymous-signed URLs.
                        //
                        // Additionally, WEB_REMIX is a browser web client.  Chrome always sends
                        // Sec-Fetch-* headers; YouTube CDN may validate them for web-client URLs.
                        host.contains("googlevideo.com") -> {
                            builder.header("Cookie", cookie)
                                   .header("Origin", "https://music.youtube.com")
                                   .header("Referer", "https://music.youtube.com/")
                            if (hasPot) {
                                builder.header("Sec-Fetch-Dest", "audio")
                                       .header("Sec-Fetch-Mode", "cors")
                                       .header("Sec-Fetch-Site", "cross-site")
                            }
                        }
                    }
                }

                // X-Goog-Visitor-Id ties the CDN request to the visitor session that the
                // PoToken was generated for — without it the CDN cannot correlate pot= to a
                // valid session and returns 403.
                val vd = YtPlayerUtils.cachedVisitorData
                if (vd != null) {
                    builder.header("X-Goog-Visitor-Id", vd)
                }

                val response = chain.proceed(builder.build())

                // Log 403 CDN response bodies — the body often contains the exact failure reason
                // (e.g. "Video unavailable", IP mismatch, missing auth) which is invisible from
                // the ExoPlayer error alone.
                if (response.code == 403 && host.contains("googlevideo.com")) {
                    val body = response.peekBody(400).string().take(300)
                    AppLogger.e(TAG, "CDN 403 body: $body")
                    AppLogger.e(TAG, "CDN 403 url: ${req.url.toString().take(120)}")
                }

                response
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
            val watchUrl = if (cacheKey.startsWith("http")) cacheKey
                           else "https://music.youtube.com/watch?v=$cacheKey"

            // Only skip resolution for fully-completed ExoPlayer downloads.
            // The download cache is guaranteed to contain the full file in that case,
            // so ExoPlayer can seek anywhere (including to the moov atom at the end of
            // an mp4 file) without needing a live stream URL.
            if (com.streamcloud.app.data.downloads.YtMusicDownloadUtil.isDownloaded(watchUrl)) {
                return@Factory dataSpec
            }

            // Legacy MusicDownloader files stored as real paths in the library DB.
            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            val localPath = runBlocking(Dispatchers.IO) { dao.byUrl(watchUrl)?.localPath }
            if (localPath != null && !localPath.startsWith("cache:") && File(localPath).exists()) {
                return@Factory dataSpec.withUri(localPath.toUri())
            }

            // Always resolve to the real stream URL for everything else.
            // CacheDataSource handles player-cache hits transparently — there is no need
            // for an early-return here, and doing so would leave the raw watch URL as the
            // HTTP fallback, causing ExoPlayer to receive an HTML page when it seeks to an
            // uncached range (e.g. the moov atom at the end of a mp4 stream).
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

        // Innertube is the primary resolver (matches Metrolist's YTPlayerUtils.playerResponseForPlayback).
        // Our client chain now includes ANDROID_VR_1_43 / ANDROID_VR_1_61 which return plain CDN
        // URLs that never need n-parameter descrambling — fast and reliable.
        // NewPipe is kept only as a last resort for edge cases where all Innertube clients fail.
        val innertubeResult = runBlocking(Dispatchers.IO) {
            runCatching { YtPlayerUtils.resolveAudioFormatInfo(videoId) }
        }
        val info = innertubeResult.getOrNull()
        if (info != null) {
            val expiryMs = now + (info.expiresInSeconds - 300).coerceAtLeast(60) * 1_000L
            StreamUrlCache.put(videoId, info.url, info.userAgent, expiryMs)
            AppLogger.i(TAG, "Innertube resolved $videoId itag=${info.itag} (cached ${(expiryMs - now) / 1000}s)")
            return Pair(info.url, info.userAgent)
        }
        AppLogger.w(TAG, "Innertube failed for $videoId: ${innertubeResult.exceptionOrNull()?.message}")

        // NewPipe last resort — fetches a watch page to descramble stream URLs; slower but
        // can succeed for edge cases where all Innertube clients are blocked.
        val npUserAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
        val ytWatchUrl = "https://www.youtube.com/watch?v=$videoId"
        val npResult = runBlocking(Dispatchers.IO) {
            runCatching { NewPipeRepository.resolveAudioStream(ytWatchUrl) }
        }
        val npUrl = npResult.getOrNull()
        if (npUrl != null) {
            StreamUrlCache.put(videoId, npUrl, npUserAgent, now + 3_600_000L)
            AppLogger.i(TAG, "NewPipe fallback resolved $videoId (cached 1h)")
            return Pair(npUrl, npUserAgent)
        }

        val err = "Innertube and NewPipe both failed to resolve stream for $videoId"
        AppLogger.e(TAG, err, innertubeResult.exceptionOrNull())
        error(err)
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



    private fun buildCustomLayout(): List<CommandButton> {
        val repeatMode = session?.player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val likeIcon = if (isCurrentLiked) R.drawable.ic_notif_fav_filled else R.drawable.ic_notif_fav
        val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_notif_repeat_one else R.drawable.ic_notif_repeat
        @Suppress("DEPRECATION")
        val likeBtn = CommandButton.Builder()
            .setIconResId(likeIcon)
            .setSessionCommand(LIKE_COMMAND)
            .setDisplayName(if (isCurrentLiked) "Unlike" else "Like")
            .build()
        @Suppress("DEPRECATION")
        val repeatBtn = CommandButton.Builder()
            .setIconResId(repeatIcon)
            .setSessionCommand(REPEAT_COMMAND)
            .setDisplayName("Repeat")
            .build()
        return listOf(likeBtn, repeatBtn)
    }

    private fun refreshLikedState() {
        ioScope.launch {
            val url = session?.player?.currentMediaItem?.mediaId ?: return@launch
            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            isCurrentLiked = dao.isLiked(url).first() ?: false
            session?.setCustomLayout(buildCustomLayout())
        }
    }

    private suspend fun toggleLike() {
        val s = session ?: return
        val url = s.player.currentMediaItem?.mediaId ?: return
        val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
        val currentlyLiked = dao.isLiked(url).first() ?: false
        if (currentlyLiked) {
            dao.setLikedAt(url, null)
        } else {
            val existing = dao.byUrl(url)
            if (existing != null) {
                dao.setLikedAt(url, System.currentTimeMillis())
            } else {
                val meta = s.player.currentMediaItem?.mediaMetadata
                dao.upsert(
                    TrackEntity(
                        url = url,
                        title = meta?.title?.toString() ?: "",
                        artist = meta?.artist?.toString() ?: "",
                        durationSec = 0L,
                        thumbnail = meta?.artworkUri?.toString(),
                        likedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        isCurrentLiked = !currentlyLiked
        s.setCustomLayout(buildCustomLayout())
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(buildRoot(), params))

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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val superResult = super.onConnect(session, controller)
            val sessionCommands = superResult.availableSessionCommands.buildUpon()
                .add(LIKE_COMMAND)
                .add(REPEAT_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(buildCustomLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_LIKE -> ioScope.launch { toggleLike() }
                ACTION_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    session.setCustomLayout(buildCustomLayout())
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }



    private fun buildRoot(): MediaItem = folder(ROOT_ID, "StreamCloud")

    private fun rootChildren(): List<MediaItem> = listOf(
        folder(HOME_ID, "Home"),
        folder(LIBRARY_ID, "Your Library"),
    )

    private fun homeChildren(): List<MediaItem> = listOf(
        playlist(RECENT_ID, "Recently played"),
        playlist(ON_REPEAT_ID, "On repeat"),
    )


    private fun libraryChildren(): List<MediaItem> {
        val fixed = listOf(
            playlist(LIKED_ID, "Liked Music"),
            playlist(DOWNLOADED_ID, "Downloads"),
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
        const val YT_PLAYLIST_PREFIX = "ytpl_"

        const val ACTION_LIKE   = "com.streamcloud.app.action.like"
        const val ACTION_REPEAT = "com.streamcloud.app.action.repeat"
        val LIKE_COMMAND   = SessionCommand(ACTION_LIKE,   Bundle.EMPTY)
        val REPEAT_COMMAND = SessionCommand(ACTION_REPEAT, Bundle.EMPTY)
    }
}
