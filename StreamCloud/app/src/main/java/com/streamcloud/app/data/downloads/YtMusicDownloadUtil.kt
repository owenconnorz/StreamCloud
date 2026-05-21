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
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackEntity
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * On STATE_COMPLETED the Room TrackEntity for the song gets localPath set to the
 * sentinel value "cache:<watchUrl>". This makes dao.downloaded() (which queries
 * WHERE local_path IS NOT NULL) include Media3-downloaded songs in the Downloads
 * library. The playback service's File(localPath).exists() check returns false for
 * the sentinel (which is intentional — playback then hits the download cache via
 * CacheDataSource as normal). On removal/failure the sentinel is cleared.
 */
@OptIn(UnstableApi::class)
object YtMusicDownloadUtil {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    syncRoomLocalPath(ctx, download)
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    downloads.update { map ->
                        map.toMutableMap().apply { remove(download.request.id) }
                    }
                    clearRoomLocalPath(ctx, download.request.id)
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

    /**
     * When a download completes, write a sentinel `localPath = "cache:<watchUrl>"`
     * into Room so that [TrackDao.downloaded] (WHERE local_path IS NOT NULL) includes
     * this song in the Downloads library tile. The sentinel is not a real file path —
     * the playback service checks File(localPath).exists() which returns false, so it
     * falls through to the download cache (CacheDataSource) as intended.
     *
     * On failure, removal, or stop the sentinel is cleared so the song correctly
     * leaves the Downloads list.
     */
    private fun syncRoomLocalPath(context: Context, download: Download) {
        val watchUrl = download.request.id
        scope.launch {
            val dao = LibraryDb.get(context).tracks()
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    val existing = runCatching { dao.byUrl(watchUrl) }.getOrNull()
                    if (existing != null) {
                        dao.setLocalPath(watchUrl, "cache:$watchUrl")
                    } else {
                        // Song might not be in Room yet (e.g., downloaded via Android Auto).
                        // Insert a minimal record so it shows up in the Downloads list.
                        val title = runCatching {
                            String(download.request.data, Charsets.UTF_8)
                        }.getOrDefault(watchUrl)
                        dao.upsert(
                            TrackEntity(
                                url = watchUrl,
                                title = title,
                                artist = "",
                                durationSec = 0,
                                thumbnail = null,
                                localPath = "cache:$watchUrl",
                            ),
                        )
                    }
                }
                Download.STATE_FAILED,
                Download.STATE_STOPPED,
                Download.STATE_REMOVING -> {
                    clearRoomLocalPath(context, watchUrl)
                }
                else -> Unit
            }
        }
    }

    private fun clearRoomLocalPath(context: Context, watchUrl: String) {
        scope.launch {
            runCatching {
                val dao = LibraryDb.get(context).tracks()
                val existing = dao.byUrl(watchUrl)
                // Only clear the sentinel we wrote — don't touch real OkHttp localPaths.
                if (existing?.localPath?.startsWith("cache:") == true) {
                    dao.setLocalPath(watchUrl, null)
                }
            }
        }
    }

    fun downloadNotificationHelper(context: Context): DownloadNotificationHelper =
        DownloadNotificationHelper(context.applicationContext, MusicExoDownloadService.CHANNEL_ID)

    fun getDownload(watchUrl: String): Flow<Download?> = downloads.map { it[watchUrl] }

    fun isDownloaded(watchUrl: String): Boolean =
        downloads.value[watchUrl]?.state == Download.STATE_COMPLETED

    fun downloadProgress(watchUrl: String): Float =
        downloads.value[watchUrl]?.percentDownloaded?.div(100f) ?: 0f
}
