package com.aioweb.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
import com.aioweb.app.MainActivity
import com.aioweb.app.data.downloads.DownloadCaches
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.library.TrackEntity
import com.aioweb.app.data.newpipe.NewPipeRepository
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
 * Foreground media-session service for music playback + **Android Auto root**.
 *
 * We extend [MediaLibraryService] (not just `MediaSessionService`) so Auto can
 * browse our content tree. The shape of the tree intentionally mirrors
 * Spotify's Auto experience:
 *
 *   ROOT
 *   ├─ home              (Spotify-style "Made for you" — most-played first)
 *   │   ├─ recently_played      (browsable → child rows of recent tracks)
 *   │   └─ on_repeat            (browsable → child rows of top play-count tracks)
 *   └─ library           (browsable; "Your library" section)
 *       ├─ liked_songs   (browsable → all liked tracks)
 *       └─ downloaded    (browsable → offline tracks, the only ones that play
 *                         in a car *without* internet, like Spotify Premium DLs)
 *
 * Items are playable when `mediaMetadata.isPlayable = true`; browsable when
 * `isBrowsable = true`. Android Auto renders them automatically as cards
 * with title, subtitle, artwork.
 *
 * ── Two-cache architecture (Metrolist parity) ──────────────────────────────
 * [DownloadCaches.downloadCache] is the outer [CacheDataSource] layer (read-only
 * from the player's perspective — `.setCacheWriteDataSinkFactory(null)`).
 * Songs explicitly downloaded by the user live here permanently.
 *
 * [DownloadCaches.playerCache] is the inner layer (LRU 256 MB).  Streams that
 * are played but not explicitly downloaded are cached here temporarily.
 *
 * The [ResolvingDataSource.Factory] wrapping both checks the caches first; only
 * if the content is absent does it call NewPipe to resolve the live stream URL.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var audioFx: AudioFx? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var playerCache: SimpleCache
    private lateinit var downloadCache: SimpleCache

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
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivityIntent)
            .build()

        audioFx = AudioFx(applicationContext, player.audioSessionId).also { it.start() }
    }

    /**
     * Builds the Metrolist-style two-cache data source factory:
     *
     *   ResolvingDataSource
     *     └─ CacheDataSource(downloadCache, setCacheWriteDataSinkFactory = null)  ← checks downloads first
     *           └─ CacheDataSource(playerCache)                                   ← then LRU stream cache
     *                 └─ DefaultHttpDataSource                                    ← actual network
     *
     * The [ResolvingDataSource.Factory] resolver fires when neither cache holds the
     * content. It checks the legacy `localPath` in Room (old-style OkHttp downloads)
     * and then falls back to NewPipe stream resolution.
     */
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
            ) {
                return@Factory dataSpec
            }

            val watchUrl = if (cacheKey.startsWith("http")) cacheKey
                           else "https://music.youtube.com/watch?v=$cacheKey"

            val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
            val localPath = runBlocking(Dispatchers.IO) {
                dao.byUrl(watchUrl)?.localPath
            }
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
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────
    // MediaLibrarySession callback — Android Auto browse tree
    // ──────────────────────────────────────────────────────────────────────

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(buildBrowseRoot(), params),
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = browsableForId(mediaId)
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
                val children = when (parentId) {
                    ROOT_ID -> rootChildren()
                    HOME_ID -> homeChildren()
                    LIBRARY_ID -> libraryChildren()
                    RECENT_ID -> trackRows(LibraryDb.get(this@MusicPlaybackService).tracks().recent().first())
                    LIKED_ID -> trackRows(LibraryDb.get(this@MusicPlaybackService).tracks().liked().first())
                    DOWNLOADED_ID -> trackRows(LibraryDb.get(this@MusicPlaybackService).tracks().downloaded().first())
                    ON_REPEAT_ID -> trackRows(LibraryDb.get(this@MusicPlaybackService).tracks().mostPlayed().first())
                    else -> emptyList()
                }
                fut.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }
            return fut
        }

        /**
         * Android Auto calls this when the driver taps a playable card.
         * With the [ResolvingDataSource.Factory] now handling all resolution, we just need
         * to return MediaItems with a stable cache key and URI — ExoPlayer resolves at
         * the data layer.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            return Futures.immediateFuture(
                mediaItems.map { attachUri(it) }.toMutableList(),
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tree builders — keep these dumb & data-only so they're easy to debug.
    // ──────────────────────────────────────────────────────────────────────

    private fun buildBrowseRoot(): MediaItem = browsable(
        id = ROOT_ID, title = "AioWeb Music",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

    private fun rootChildren(): List<MediaItem> = listOf(
        browsable(HOME_ID, "Home", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(LIBRARY_ID, "Your Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    private fun homeChildren(): List<MediaItem> = listOf(
        browsable(RECENT_ID, "Recently played", MediaMetadata.MEDIA_TYPE_PLAYLIST),
        browsable(ON_REPEAT_ID, "On repeat", MediaMetadata.MEDIA_TYPE_PLAYLIST),
    )

    private fun libraryChildren(): List<MediaItem> = listOf(
        browsable(LIKED_ID, "Liked songs", MediaMetadata.MEDIA_TYPE_PLAYLIST),
        browsable(DOWNLOADED_ID, "Downloads", MediaMetadata.MEDIA_TYPE_PLAYLIST),
    )

    private fun browsableForId(id: String): MediaItem? = when (id) {
        ROOT_ID -> buildBrowseRoot()
        HOME_ID -> browsable(HOME_ID, "Home", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        LIBRARY_ID -> browsable(LIBRARY_ID, "Your Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        RECENT_ID -> browsable(RECENT_ID, "Recently played", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        ON_REPEAT_ID -> browsable(ON_REPEAT_ID, "On repeat", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        LIKED_ID -> browsable(LIKED_ID, "Liked songs", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        DOWNLOADED_ID -> browsable(DOWNLOADED_ID, "Downloads", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        else -> null
    }

    private fun trackRows(tracks: List<TrackEntity>): List<MediaItem> = tracks.map { t ->
        MediaItem.Builder()
            .setMediaId(t.url)
            .setUri(t.url)
            .setCustomCacheKey(t.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(t.thumbnail?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    private fun browsable(id: String, title: String, mediaType: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build(),
            )
            .build()

    /**
     * Ensures a [MediaItem] has a URI and stable cache key set.
     * Used for items coming from the browse tree (no URI) and from the in-app player
     * (already resolved). With [ResolvingDataSource], resolution now happens at the
     * data layer — we only need to set the URI so ExoPlayer knows what to prepare.
     */
    private fun attachUri(item: MediaItem): MediaItem {
        if (item.localConfiguration?.uri != null) return item
        val id = item.mediaId.ifBlank { return item }
        return item.buildUpon()
            .setUri(id)
            .setCustomCacheKey(id)
            .build()
    }

    companion object {
        const val ROOT_ID = "aioweb_root"
        const val HOME_ID = "aioweb_home"
        const val LIBRARY_ID = "aioweb_library"
        const val RECENT_ID = "aioweb_recent"
        const val ON_REPEAT_ID = "aioweb_on_repeat"
        const val LIKED_ID = "aioweb_liked"
        const val DOWNLOADED_ID = "aioweb_downloaded"
    }
}
