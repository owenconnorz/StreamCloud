package com.streamcloud.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.streamcloud.app.MainActivity
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.downloads.DownloadCaches
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackDao
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.newpipe.NewPipeRepository
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

    private val sl by lazy { ServiceLocator.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()

        playerCache = DownloadCaches.playerCache(this)
        downloadCache = DownloadCaches.downloadCache(this)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(buildDataSourceFactory())

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { playWhenReady = false }

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
        ioScope.launch {
            sl.settings.ytMusicCookie.collect { cookie ->
                if (cookie.isNotBlank()) {
                    ytLibrary = YtMusicLibraryRepository.sync(cookie)
                }
            }
        }
    }

    // ── Data source factory ───────────────────────────────────────────────

    private fun buildDataSourceFactory(): ResolvingDataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

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
            val length = if (dataSpec.length >= 0) dataSpec.length else 1L

            if (downloadCache.isCached(cacheKey, dataSpec.position, length) ||
                playerCache.isCached(cacheKey, dataSpec.position, length)
            ) return@Factory dataSpec

            val watchUrl = if (cacheKey.startsWith("http")) cacheKey
                           else "https://music.youtube.com/watch?v=$cacheKey"

            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            val localPath = runBlocking(Dispatchers.IO) { dao.byUrl(watchUrl)?.localPath }
            if (localPath != null && File(localPath).exists()) {
                return@Factory dataSpec.withUri(localPath.toUri())
            }

            val streamUrl = runBlocking(Dispatchers.IO) {
                NewPipeRepository.resolveAudioStream(watchUrl)
            }
            dataSpec.withUri(streamUrl.toUri())
        }
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
