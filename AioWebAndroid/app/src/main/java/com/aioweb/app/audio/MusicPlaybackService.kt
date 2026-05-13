package com.aioweb.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.aioweb.app.MainActivity
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
 */
@UnstableApi
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var cache: SimpleCache? = null
    private var audioFx: AudioFx? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ── Audio cache (LRU, 256 MB) ─────────────────────────────────────
        val cacheDir = File(cacheDir, "audio-cache").apply { mkdirs() }
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(this),
        )

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache!!)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheFactory)

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
        cache?.release()
        cache = null
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
         * Android Auto calls this when the driver taps a playable card. We
         * have to return MediaItems whose `localConfiguration.uri` is a
         * direct-playable stream — Auto won't trigger NewPipe extraction for
         * us. So we resolve every item here, in parallel.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val fut = SettableFuture.create<MutableList<MediaItem>>()
            ioScope.launch {
                fut.set(mediaItems.map { resolveForPlayback(it) }.toMutableList())
            }
            return fut
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
     * Resolves a browse-time MediaItem (no URI) into a playable one. Tries
     * the local-file path first (if the track was previously downloaded),
     * else asks [NewPipeRepository] for a streamable audio URL. Falls back
     * to the watch URL on failure so the player at least reports the error.
     */
    private suspend fun resolveForPlayback(item: MediaItem): MediaItem {
        // Already resolved (e.g. from the in-app player handing us the queue)?
        if (item.localConfiguration?.uri != null) return item
        val url = item.mediaId.ifBlank { return item }
        val dao = LibraryDb.get(this@MusicPlaybackService).tracks()
        val cached = runCatching { dao.byUrl(url) }.getOrNull()
        val playable: String = cached?.localPath?.takeIf { File(it).exists() }
            ?: runCatching { NewPipeRepository.resolveAudioStream(url) }.getOrNull()
            ?: return item
        return item.buildUpon().setUri(playable).build()
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
