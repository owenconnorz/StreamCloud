package com.streamcloud.app.data.downloads

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.streamcloud.app.data.SettingsRepository
import com.streamcloud.app.data.library.FormatEntity
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object YtMusicDownloadUtil {

    private const val MAX_PARALLEL_DOWNLOADS = 5


    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var _downloadManager: DownloadManager? = null

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())


    private val downloadHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun downloadManager(context: Context): DownloadManager {
        _downloadManager?.let { return it }

        val ctx = context.applicationContext
        val downloadCache = DownloadCaches.downloadCache(ctx)
        val playerCache  = DownloadCaches.playerCache(ctx)
        val connectivityManager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val settings = SettingsRepository(ctx)

        // Use playerCache (not downloadCache) in the upstream chain.
        // DownloadManager already writes directly to downloadCache; having downloadCache
        // in the factory too causes simultaneous read/write lock contention that
        // serialises every download segment — the main reason downloads were slow.
        // playerCache lets us reuse data already buffered from streaming, harmlessly.
        val dataSourceFactory = ResolvingDataSource.Factory(
            CacheDataSource.Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(downloadHttpClient)
                        .setUserAgent(
                            "com.google.android.apps.youtube.music/7.27.52 " +
                                "(Linux; U; Android 11) gzip",
                        ),
                ),
        ) { dataSpec ->
            val watchUrl = dataSpec.uri.toString()
            val cacheKey  = dataSpec.key ?: watchUrl


            if (downloads.value[cacheKey]?.state == Download.STATE_COMPLETED) {
                return@Factory dataSpec
            }





            val requestLength = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.isCached(cacheKey, dataSpec.position, requestLength)) {
                return@Factory dataSpec
            }




            urlCache[cacheKey]?.let { (cachedUrl, expiry) ->
                if (System.currentTimeMillis() < expiry) {
                    return@Factory dataSpec.withUri(cachedUrl.toUri())
                }
                urlCache.remove(cacheKey)
            }


            val videoId = watchUrl.substringAfter("v=", "").substringBefore("&")

            val (streamUrl, ttlMs) = runBlocking {
                if (videoId.isNotBlank()) {
                    val dao      = LibraryDb.get(ctx).formats()
                    val stored   = dao.byVideoId(videoId)
                    val qualPref = settings.audioQuality.first()
                    val isMetered = connectivityManager.isActiveNetworkMetered
                    val preferHigh = when (qualPref) {
                        "high"  -> true
                        "low"   -> false
                        else    -> !isMetered
                    }

                    val info = YtPlayerUtils.resolveAudioFormatInfo(
                        videoId          = videoId,
                        preferItag       = stored?.itag,
                        preferHighQuality = preferHigh,
                    )

                    if (info != null) {

                        val codecsRaw = info.mimeType.substringAfter("codecs=", "")
                            .removeSurrounding("\"")
                        dao.upsert(
                            FormatEntity(
                                videoId       = videoId,
                                itag          = info.itag,
                                mimeType      = info.mimeType.split(";")[0],
                                codecs        = codecsRaw,
                                bitrate       = info.bitrate,
                                sampleRate    = info.sampleRate,
                                contentLength = info.contentLength ?: 0L,
                                loudnessDb    = info.loudnessDb,
                            ),
                        )

                        // &range=0-N signals a one-shot download to YouTube's CDN;
                        // without it the CDN throttles to streaming speed (~100 KB/s).
                        // Use actual contentLength when available; fall back to 50 MB
                        // which safely covers any audio track at any quality.
                        val rangeEnd = if (info.contentLength != null && info.contentLength > 0)
                            info.contentLength else 50_000_000L
                        val downloadUrl = "${info.url}&range=0-$rangeEnd"
                        Pair(downloadUrl, info.expiresInSeconds * 1_000L)
                    } else {

                        Pair(NewPipeRepository.resolveAudioStream(watchUrl), 3L * 60 * 60 * 1000)
                    }
                } else {
                    Pair(NewPipeRepository.resolveAudioStream(watchUrl), 3L * 60 * 60 * 1000)
                }
            }


            urlCache[cacheKey] = streamUrl to (System.currentTimeMillis() + ttlMs)
            dataSpec.withUri(streamUrl.toUri())
        }

        val manager = DownloadManager(
            ctx,
            DownloadCaches.databaseProvider(ctx),
            downloadCache,
            dataSourceFactory,
            // Inline executor: tasks run on DownloadManager's own thread.
            // A thread pool here adds queuing overhead and can stall when
            // runBlocking URL-resolution holds pool threads — matching Metrolist.
            Executor(Runnable::run),
        ).apply {
            maxParallelDownloads = 3
            minRetryCount = 5
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
                    urlCache.remove(download.request.id)
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
                        val title = runCatching {
                            String(download.request.data, Charsets.UTF_8)
                        }.getOrDefault(watchUrl)
                        com.streamcloud.app.data.library.TrackEntity(
                            url = watchUrl,
                            title = title,
                            artist = "",
                            durationSec = 0,
                            thumbnail = null,
                            localPath = "cache:$watchUrl",
                        ).also { dao.upsert(it) }
                    }
                }
                Download.STATE_FAILED,
                Download.STATE_STOPPED,
                Download.STATE_REMOVING -> clearRoomLocalPath(context, watchUrl)
                else -> Unit
            }
        }
    }

    private fun clearRoomLocalPath(context: Context, watchUrl: String) {
        scope.launch {
            runCatching {
                val dao = LibraryDb.get(context).tracks()
                val existing = dao.byUrl(watchUrl)
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
