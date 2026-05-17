package com.aioweb.app.data.downloads

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
 *  • [playerCache]   — LRU 256 MB ephemeral buffer for streamed playback (same role as
 *                      Metrolist's @PlayerCache). Old entries are evicted when the cache is full.
 *
 *  • [downloadCache] — Permanent store for explicitly downloaded songs (Metrolist's @DownloadCache).
 *                      [NoOpCacheEvictor] means nothing is ever evicted automatically; the user
 *                      must explicitly delete a download.
 *
 * Both caches share a single [StandaloneDatabaseProvider] which maps to a SQLite database in
 * [Context.filesDir].  This is safe — each [SimpleCache] directory acts as its own namespace
 * within the database.
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
            val dir = File(context.applicationContext.filesDir, "exoplayer").apply { mkdirs() }
            SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                databaseProvider(context),
            ).also { _playerCache = it }
        }

    @Synchronized
    fun downloadCache(context: Context): SimpleCache =
        _downloadCache ?: run {
            val dir = File(context.applicationContext.filesDir, "download").apply { mkdirs() }
            SimpleCache(
                dir,
                NoOpCacheEvictor(),
                databaseProvider(context),
            ).also { _downloadCache = it }
        }
}
