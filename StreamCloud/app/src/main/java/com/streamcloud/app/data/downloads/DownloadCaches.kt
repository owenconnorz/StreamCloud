package com.streamcloud.app.data.downloads

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

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
