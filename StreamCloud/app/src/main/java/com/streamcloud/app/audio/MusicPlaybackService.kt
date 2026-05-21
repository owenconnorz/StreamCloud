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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

/**
 * Foreground [MediaLibraryService] for music playback and Android Auto / Automotive OS.
 *
 * ── Android Auto browse tree (mirrors Metrolist) ───────────────────────────
 *
 *   ROOT
 *   ├─ Home
 *   │   ├─ Recently played   (last 50 tracks from Room)
 *   │   └─ On repeat         (top 50 most-played from Room)
 *   └─ Library
 *       ├─ Liked Music        (YT Music liked songs — synced from YTM)
 *       ├─ Downloads          (offline tracks from Room)
 *       └─ <each YTM playlist> (fetched on demand via playlistTracks)
 *
 * Playlist IDs that start with [YT_PLAYLIST_PREFIX] are handled by fetching
 * their tracks live from [YtMusicLibraryRepository.playlistTracks].
 *
 * Search is supported via [LibraryCallback.onSearch]; results come from
 * [NewPipeRepository.searchSongs].
 *
 * ── Two-cache playback pipeline ──────────────────────────────────────────
 * downloadCache (permanent) → playerCache (LRU 256 MB) → HTTP (NewPipe resolved)
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var audioFx: AudioFx? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var playerCache: SimpleCache
    private lateinit var downloadCache: SimpleCache

    /** Cached YT Music library — refreshed at startup and on cookie change. */
    @Volatile private var ytLibrary: YtMusicLibrary = YtMusicLibrary()

    /**
     * Kept in sync with the DataStore cookie so the OkHttp stream interceptor
     * can read it without blocking.  Also forwarded to [YtPlayerUtils] so that
     * Innertube player requests are sent as a signed-in user.
     */
    @Volatile private var ytMusicCookieForStream: String = ""

    // Stream URL resolution is delegated to the app-wide [StreamUrlCache] singleton.
    // That object is also populated by [YtPlaylistScreen] when a playlist opens so
    // that every track's URL is pre-resolved before the user taps play.

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
            // Pause automatically on incoming calls and when another app (YouTube,
            // podcasts, etc.) requests audio focus, then resume when they release it.
            .setAudioAttributes(musicAudioAttrs, /* handleAudioFocus= */ true)
            // Pause when headphones are unplugged.
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = false
                // Auto-skip unresolvable tracks (PoToken-blocked, region-restricted,
                // age-gated) so the rest of the playlist keeps playing.
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val isIoError = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        if (isIoError && hasNextMediaItem()) {
                            AppLogger.w(
                                "MusicPlaybackService",
                                "Skipping unresolvable track — ${error.message?.take(80)}",
                            )
                            seekToNextMediaItem()
                            play()
                        }
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

        // Sync YTM library at startup + re-sync whenever the signed-in cookie changes.
        // Also forward the cookie to YtPlayerUtils (Innertube player requests) and
        // keep ytMusicCookieForStream up-to-date for the OkHttp stream interceptor.
        ioScope.launch {
            sl.settings.ytMusicCookie.collect { cookie ->
                ytMusicCookieForStream = cookie
                YtPlayerUtils.ytMusicCookie = cookie
                if (cookie.isNotBlank()) {
                    ytLibrary = YtMusicLibraryRepository.sync(cookie)
                }
            }
        }
    }

    // ── Data source factory ───────────────────────────────────────────────

    private fun buildDataSourceFactory(): ResolvingDataSource.Factory {
        // OkHttpDataSource is used instead of DefaultHttpDataSource because
        // YouTube CDN URLs produced by the IOS/IPADOS Innertube clients require
        // OkHttp's HTTP/2 stack and redirect-handling — Android's HttpURLConnection
        // returns HTTP 403 on the same URLs.  This matches Metrolist's approach.
        //
        // The User-Agent is NOT set here; it is applied per-DataSpec in the
        // ResolvingDataSource lambda below so it always matches the client that
        // produced the stream URL (IOS UA for IOS URLs, ANDROID_MUSIC UA for
        // ANDROID_MUSIC URLs, etc.).
        val streamOkHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                // Attach the session cookie if the user is signed in.
                // YouTube CDN doesn't strictly require the cookie, but having it
                // in the request makes the CDN treat the client as authenticated,
                // which avoids throttling on certain IPs and regions.
                val cookie = ytMusicCookieForStream
                val req = if (cookie.isNotBlank())
                    chain.request().newBuilder().header("Cookie", cookie).build()
                else chain.request()
                chain.proceed(req)
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
            // Use 1 byte as the probe length when ExoPlayer sends an open-ended
            // request (length = -1) — enough to determine if any bytes are cached.
            val probeLen = if (dataSpec.length >= 0) dataSpec.length else 1L

            // Fast path: bytes already in one of the two caches — no network needed.
            if (downloadCache.isCached(cacheKey, dataSpec.position, probeLen) ||
                playerCache.isCached(cacheKey, dataSpec.position, probeLen)
            ) return@Factory dataSpec

            val watchUrl = if (cacheKey.startsWith("http")) cacheKey
                           else "https://music.youtube.com/watch?v=$cacheKey"

            // Local file path (legacy OkHttp downloads).
            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            val localPath = runBlocking(Dispatchers.IO) { dao.byUrl(watchUrl)?.localPath }
            if (localPath != null && File(localPath).exists()) {
                return@Factory dataSpec.withUri(localPath.toUri())
            }

            val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")
            val (streamUrl, userAgent) = resolveStreamUrl(videoId, watchUrl)

            // Apply the UA that matches the Innertube client that produced this URL.
            // YouTube CDN binds stream URLs to the requesting client — a different
            // UA returns HTTP 403.
            dataSpec.buildUpon()
                .setUri(streamUrl.toUri())
                .setHttpRequestHeaders(
                    dataSpec.httpRequestHeaders + mapOf("User-Agent" to userAgent)
                )
                .build()
        }
    }

    /**
     * Resolve the playable stream URL for [videoId], with an in-memory expiry
     * cache so that repeated ExoPlayer data-spec calls for the same track
     * (seek probes, range requests, rebuffer fills) never incur a second
     * network round-trip.
     *
     * Resolution order (mirrors Metrolist):
     *  1. [StreamUrlCache] hit and URL not yet expired → return immediately
     *     (also populated by [YtPlaylistScreen] warmup before the user taps play)
     *  2. [YtPlayerUtils.resolveAudioFormatInfo] — single Innertube POST,
     *     typically 300–800 ms; stores URL with CDN expiry - 5 min buffer
     *  3. [NewPipeRepository.resolveAudioStream] fallback — parses watch page
     *     HTML (~3–8 s); result cached for 1 hour
     *
     * Throws if both resolvers fail so ExoPlayer can surface a proper error
     * to the UI instead of silently crashing on a null URI.
     *
     * Returns `Pair(streamUrl, userAgent)` — the UA must be passed to ExoPlayer
     * because YouTube CDN binds stream URLs to the requesting client.
     */
    private fun resolveStreamUrl(videoId: String, watchUrl: String): Pair<String, String> {
        val now = System.currentTimeMillis()

        // 1. Shared cache hit (may have been pre-warmed by playlist open).
        StreamUrlCache.getEntry(videoId)?.let { entry ->
            val ttl = StreamUrlCache.ttlSeconds(videoId) ?: 0
            Log.d(TAG, "StreamUrlCache hit for $videoId (ttl=${ttl}s)")
            return Pair(entry.url, entry.userAgent)
        }

        // 2. Innertube path — single POST, 300–800 ms.
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

        // 3. NewPipe fallback (slower — parses watch-page HTML + deobfuscates nsig).
        // NewPipe returns plain stream URLs; use a neutral YouTube UA for the request.
        val npUserAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
        AppLogger.w(TAG, "Innertube returned no result for $videoId — falling back to NewPipe")
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

        // Cache NewPipe URLs for 1 hour (YouTube doesn't report their expiry).
        StreamUrlCache.put(videoId, npUrl, npUserAgent, now + 3_600_000L)
        Log.d(TAG, "NewPipe resolved $videoId (cached 1 h)")
        return Pair(npUrl, npUserAgent)
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

    // ── MediaLibrarySession callback ──────────────────────────────────────

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

        /**
         * Called by Auto when the user taps a playable item.
         * With [ResolvingDataSource], stream resolution is deferred to the data layer;
         * we just need a stable URI and cache key on the item.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::attachUri).toMutableList())

        /**
         * Android Auto search bar — delegates to [NewPipeRepository.searchSongs].
         * Results are returned as playable [MediaItem]s (no grouping needed for Auto).
         */
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

        /**
         * Playback resumption — Auto shows a "Resume" card when the app is idle.
         * We return the most recently played track from Room.
         */
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

    // ── Browse tree builders ──────────────────────────────────────────────

    private fun buildRoot(): MediaItem = folder(ROOT_ID, "StreamCloud")

    private fun rootChildren(): List<MediaItem> = listOf(
        folder(HOME_ID, "Home"),
        folder(LIBRARY_ID, "Your Library"),
    )

    private fun homeChildren(): List<MediaItem> = listOf(
        playlist(RECENT_ID, "Recently played"),
        playlist(ON_REPEAT_ID, "On repeat"),
    )

    /**
     * Library section:
     *  - Liked Music  (YTM liked songs — live from YtMusicLibrary cache)
     *  - Downloads    (Room offline tracks)
     *  - All YTM user playlists (each browsable, tracks fetched on demand)
     */
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

    /** Returns liked songs: YTM liked songs if available, Room local likes as fallback. */
    private suspend fun likedChildren(): List<MediaItem> {
        val ytmLiked = ytLibrary.likedSongs
        if (ytmLiked.isNotEmpty()) return ytmLiked.map(::ytmSongItem)
        val local = runCatching {
            LibraryDb.get(this@MusicPlaybackService).tracks().liked().first()
        }.getOrElse { emptyList() }
        return local.map(::trackEntityItem)
    }

    /** Fetch tracks for a YT Music playlist identified by [playlistId]. */
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

    // ── MediaItem factory helpers ─────────────────────────────────────────

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

        /** Prefix for browse IDs that represent YT Music user playlists. */
        const val YT_PLAYLIST_PREFIX = "ytpl_"
    }
}
