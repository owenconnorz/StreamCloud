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
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
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
 * Architecture mirrors Metrolist's DownloadUtil, adapted for this app's stream
 * resolution via YtPlayerUtils (Innertube ANDROID_MUSIC, ~300–800 ms) with
 * NewPipe as a fallback.
 *
 *  DownloadRequest(id = watchUrl, uri = watchUrl)
 *    → ResolvingDataSource (always resolves a fresh stream URL for uncached bytes)
 *    → CacheDataSource (downloadCache) downloads bytes, keyed by the stable watchUrl
 *
 * Key fix vs. original: the resolver no longer does an early-return when any cached
 * bytes exist (the old `isCached(key, pos, 1)` check).  Returning the watch-page
 * URL for the "uncached tail" on a retried download caused CacheDataSource to hit
 * the YouTube watch page (HTML) instead of the audio stream, stalling downloads.
 * Now we only skip resolution when the download is fully STATE_COMPLETED.
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
                            "com.google.android.apps.youtube.music/7.27.52 " +
                                "(Linux; U; Android 11) gzip",
                        )
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(20_000)
                        .setReadTimeoutMs(120_000),
                ),
        ) { dataSpec ->
            val watchUrl = dataSpec.uri.toString()
            val cacheKey  = dataSpec.key ?: watchUrl

            // Only skip resolution for FULLY completed downloads.
            // On partial downloads or retries we always need a fresh stream URL —
            // YouTube streams expire and the watch-page URL is not a valid audio source.
            if (downloads.value[cacheKey]?.state == Download.STATE_COMPLETED) {
                return@Factory dataSpec
            }

            val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")
            val streamUrl = runBlocking {
                (if (videoId.isNotBlank()) YtPlayerUtils.resolveAudioStream(videoId) else null)
                    ?: NewPipeRepository.resolveAudioStream(watchUrl)
            }
            // Replace URI with the resolved audio stream; the stable watchUrl remains
            // the cache key (set via DownloadRequest.setCustomCacheKey).
            dataSpec.withUri(streamUrl.toUri())
        }

        val manager = DownloadManager(
            ctx,
            DownloadCaches.databaseProvider(ctx),
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run),
        ).apply {
            // Metrolist uses 5 parallel downloads; more than 5 rarely helps and
            // risks hitting YouTube rate-limits.
            maxParallelDownloads = 5
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
