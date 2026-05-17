package com.streamcloud.app.data.downloads

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.streamcloud.app.data.newpipe.NewPipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executor

/**
 * Application-scoped singleton that owns the Media3 [DownloadManager] used by
 * [MusicExoDownloadService].
 *
 * Architecture mirrors Metrolist's DownloadUtil, adapted for this app's NewPipe-based
 * stream resolution instead of YTPlayerUtils:
 *
 *  DownloadRequest(id = watchUrl, uri = watchUrl)
 *    → ResolvingDataSource (intercepts URI, calls NewPipe to get the real audio stream URL)
 *    → CacheDataSource (downloadCache) downloads bytes and stores under key = watchUrl
 *
 * [downloads] is a [MutableStateFlow] that reflects the current in-memory state of all
 * downloads (restored from the persistent DownloadManager database on first access).
 */
@OptIn(UnstableApi::class)
object YtMusicDownloadUtil {

    @Volatile private var _downloadManager: DownloadManager? = null

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    @Synchronized
    fun downloadManager(context: Context): DownloadManager {
        _downloadManager?.let { return it }

        val ctx = context.applicationContext
        val downloadCache = DownloadCaches.downloadCache(ctx)

        val dataSourceFactory = ResolvingDataSource.Factory(
            CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        )
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(60_000),
                ),
        ) { dataSpec ->
            val watchUrl = dataSpec.uri.toString()
            val cacheKey = dataSpec.key ?: watchUrl
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            if (downloadCache.isCached(cacheKey, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            val streamUrl = runBlocking(Dispatchers.IO) {
                NewPipeRepository.resolveAudioStream(watchUrl)
            }
            dataSpec.withUri(streamUrl.toUri())
        }

        val manager = DownloadManager(
            ctx,
            DownloadCaches.databaseProvider(ctx),
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run),
        ).apply {
            maxParallelDownloads = 3
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { set(download.request.id, download) }
                    }
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { remove(download.request.id) }
                    }
                }
            })
        }

        val result = mutableMapOf<String, Download>()
        val cursor = manager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

        return manager.also { _downloadManager = it }
    }

    fun downloadNotificationHelper(context: Context): DownloadNotificationHelper =
        DownloadNotificationHelper(context.applicationContext, MusicExoDownloadService.CHANNEL_ID)

    fun getDownload(watchUrl: String): Flow<Download?> = downloads.map { it[watchUrl] }

    fun isDownloaded(watchUrl: String): Boolean =
        downloads.value[watchUrl]?.state == Download.STATE_COMPLETED

    fun downloadProgress(watchUrl: String): Float =
        downloads.value[watchUrl]?.percentDownloaded?.div(100f) ?: 0f
}
