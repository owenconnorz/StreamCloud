package com.streamcloud.app.data.downloads

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Application-scoped singletons for the two Media3 [SimpleCache] instances used for music:
 *
 *  • [playerCache]   — LRU 256 MB ephemeral buffer for streamed playback. Stored in
 *                      [Context.cacheDir] so Android can reclaim space when needed.
 *
 *  • [downloadCache] — Permanent store for explicitly downloaded songs (Metrolist's
 *                      @DownloadCache). Stored in [Context.getExternalFilesDir] so that
 *                      "Clear Cache" on the app settings page can never erase downloads —
 *                      only "Clear Storage / Data" can do that. Falls back to
 *                      [Context.filesDir] if external storage is unavailable.
 *                      [NoOpCacheEvictor] means nothing is ever evicted automatically.
 *
 * Both caches share a single [StandaloneDatabaseProvider] which maps to a SQLite database
 * in the app's databases directory. Each [SimpleCache] directory acts as its own namespace.
 */
@OptIn(UnstableApi::class)
object DownloadCaches {

    @Volatile private var _dbProvider: StandaloneDatabaseProvider? = null
    @Volatile private var _playerCache: SimpleCache? = null
    @Volatile private var _downloadCache: SimpleCache? = null

    @Synchronized
    fun databaseProvider(context: Context): StandaloneDatabaseProvider =
        _dbProvider
            ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { _dbProvider = it }

    @Synchronized
    fun playerCache(context: Context): SimpleCache =
        _playerCache ?: run {
            val dir = File(context.applicationContext.cacheDir, "exoplayer").apply { mkdirs() }
            SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                databaseProvider(context),
            ).also { _playerCache = it }
        }

    @Synchronized
    fun downloadCache(context: Context): SimpleCache =
        _downloadCache ?: run {
            val ctx = context.applicationContext
            val externalDir = ctx.getExternalFilesDir("exoplayer_downloads")
            val dir = (externalDir ?: File(ctx.filesDir, "exoplayer_downloads")).apply { mkdirs() }
            SimpleCache(
                dir,
                NoOpCacheEvictor(),
                databaseProvider(ctx),
            ).also { _downloadCache = it }
        }
}
