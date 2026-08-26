package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.streamcloud.app.audio.MusicController
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.downloads.MusicExoDownloadService
import com.streamcloud.app.data.downloads.YtMusicDownloadUtil
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableApi::class)
object YtPlayback {

    private const val TAG = "YtPlayback"
    const val EXTRA_VIDEO_ID = "videoId"
    const val EXTRA_WATCH_URL = "watchUrl"
    const val EXTRA_IS_MUSIC_VIDEO = "isMusicVideo"

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"


    private fun buildMediaItem(song: YtmSong): MediaItem {
        val url = watchUrl(song.videoId)
        return MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .setCustomCacheKey(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.thumbnail?.let(Uri::parse))
                    .setExtras(android.os.Bundle().apply {
                        putString(EXTRA_VIDEO_ID, song.videoId)
                        putString(EXTRA_WATCH_URL, url)
                        putBoolean(EXTRA_IS_MUSIC_VIDEO, song.isVideo)
                    })
                    .build(),
            )
            .build()
    }

    private fun primeStreams(songs: Iterable<YtmSong>) {
        YtMusicStreamResolver.prime(songs.map(YtmSong::videoId))
    }

    /**
     * Give the selected track's foreground request a brief head start before Media3 prepares.
     * The timeout never blocks the normal data-source fallback path: if the resolver is slow or
     * fails, prepare continues and consumes the same in-flight work or its recovery chain.
     */
    private suspend fun awaitForegroundStream(
        videoId: String,
        resolution: Deferred<Result<StreamUrlCache.Entry>>,
    ) {
        val result = withTimeoutOrNull(FOREGROUND_RESOLVE_HEAD_START_MS) {
            resolution.await()
        }
        when {
            result == null ->
                AppLogger.i(TAG, "Foreground stream still resolving for $videoId; preparing Media3")
            result.isSuccess ->
                AppLogger.i(TAG, "Foreground stream ready for $videoId before prepare")
            else ->
                AppLogger.w(
                    TAG,
                    "Foreground stream failed for $videoId; keeping Media3 fallback: " +
                        result.exceptionOrNull()?.message,
                )
        }
    }

    private fun upsertTrack(context: Context, song: YtmSong, bumpPlayCount: Boolean) {
        val url = watchUrl(song.videoId)
        backgroundScope.launch {
            val dao = LibraryDb.get(context).tracks()
            val cached = runCatching { dao.byUrl(url) }.getOrNull()
            val refreshed = TrackEntity(
                url = url,
                title = song.title,
                artist = song.artist,
                durationSec = song.durationSeconds ?: cached?.durationSec ?: 0L,
                thumbnail = song.thumbnail ?: cached?.thumbnail,
                likedAt = cached?.likedAt,
                lastPlayed = if (bumpPlayCount) System.currentTimeMillis() else cached?.lastPlayed,
                playCount = if (bumpPlayCount) (cached?.playCount ?: 0) + 1 else (cached?.playCount ?: 0),
                localPath = cached?.localPath,
            )
            runCatching { dao.upsert(refreshed) }
        }
    }


    suspend fun playSong(context: Context, song: YtmSong, withAutoRadio: Boolean = true) {
        // This is foreground work, not list prefetch. It shares its result with Media3 instead of
        // being cancelled and restarted when the data source promotes the selected item.
        val streamResolution = YtMusicStreamResolver.primeForPlayback(song.videoId)
        val item = buildMediaItem(song)
        upsertTrack(context, song, bumpPlayCount = true)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.setMediaItem(item)
        }
        awaitForegroundStream(song.videoId, streamResolution)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.prepare()
            controller.play()
        }
        if (withAutoRadio) startAutoRadio(context, song)
    }


    private fun startAutoRadio(context: Context, seed: YtmSong) {
        backgroundScope.launch {
            runCatching {
                // Leave the first stream's resolver and initial CDN read alone. Auto-radio is
                // speculative queue work and used to compete with a fresh song tap immediately.
                delay(AUTO_RADIO_DELAY_MS)
                val related = EndlessPlayback.relatedSongs(context, seed.videoId)
                if (related.isEmpty()) return@runCatching
                primeStreams(related)
                related.forEach { s ->
                    runCatching {
                        val item = buildMediaItem(s)
                        withContext(Dispatchers.Main) {
                            val controller = MusicController.get(context.applicationContext)
                            if (controller.currentMediaItem?.mediaId == watchUrl(seed.videoId)
                                || controller.mediaItemCount > 0) {
                                controller.addMediaItem(item)
                            }
                        }
                    }
                }
            }
        }
    }

    private const val AUTO_RADIO_DELAY_MS = 2_000L


    fun startRadioFromCurrent(context: Context, mediaIdUrl: String) {
        val videoId = mediaIdUrl
            .substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?: return
        val seed = YtmSong(
            videoId = videoId,
            title = "",
            artist = "",
            album = null,
            thumbnail = null,
            durationSeconds = null,
        )
        startAutoRadio(context, seed)
    }


    suspend fun playNext(context: Context, song: YtmSong) {
        val item = buildMediaItem(song)
        var foregroundResolution: Deferred<Result<StreamUrlCache.Entry>>? = null
        val startsPlayback = withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            if (controller.mediaItemCount == 0) {
                foregroundResolution = YtMusicStreamResolver.primeForPlayback(song.videoId)
                controller.setMediaItem(item)
                true
            } else {
                val insertAt = (controller.currentMediaItemIndex + 1)
                    .coerceIn(0, controller.mediaItemCount)
                controller.addMediaItem(insertAt, item)
                false
            }
        }
        if (startsPlayback) {
            foregroundResolution?.let { awaitForegroundStream(song.videoId, it) }
            withContext(Dispatchers.Main) {
                val controller = MusicController.get(context.applicationContext)
                controller.prepare()
                controller.play()
            }
        } else {
            primeStreams(listOf(song))
        }
    }


    suspend fun addToQueue(context: Context, song: YtmSong) {
        val item = buildMediaItem(song)
        var foregroundResolution: Deferred<Result<StreamUrlCache.Entry>>? = null
        val startsPlayback = withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            if (controller.mediaItemCount == 0) {
                foregroundResolution = YtMusicStreamResolver.primeForPlayback(song.videoId)
                controller.setMediaItem(item)
                true
            } else {
                controller.addMediaItem(item)
                false
            }
        }
        if (startsPlayback) {
            foregroundResolution?.let { awaitForegroundStream(song.videoId, it) }
            withContext(Dispatchers.Main) {
                val controller = MusicController.get(context.applicationContext)
                controller.prepare()
                controller.play()
            }
        } else {
            primeStreams(listOf(song))
        }
    }


    suspend fun playPlaylist(context: Context, songs: List<YtmSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        val activeSong = songs[safeStart]
        val streamResolution = YtMusicStreamResolver.primeForPlayback(activeSong.videoId)
        YtMusicStreamResolver.primeQueue(
            videoIds = songs.drop(safeStart + 1).map(YtmSong::videoId),
            currentIndex = 0,
        )


        val allItems = songs.map { buildMediaItem(it) }


        upsertTrack(context, songs[safeStart], bumpPlayCount = true)
        songs.indices.filter { it != safeStart }.forEach { i ->
            upsertTrack(context, songs[i], bumpPlayCount = false)
        }


        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.setMediaItems(allItems, safeStart,  0L)
        }
        awaitForegroundStream(activeSong.videoId, streamResolution)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.prepare()
            controller.play()
        }
    }


    fun downloadSong(context: Context, song: YtmSong) {
        val watchUrl = watchUrl(song.videoId)
        val downloadId = YtMusicDownloadUtil.downloadId(song.videoId)
        backgroundScope.launch {
            val dao = LibraryDb.get(context).tracks()
            val existing = runCatching { dao.byUrl(watchUrl) }.getOrNull()
            if (existing == null) {
                dao.upsert(
                    TrackEntity(
                        url = watchUrl,
                        title = song.title,
                        artist = song.artist,
                        durationSec = song.durationSeconds ?: 0L,
                        thumbnail = song.thumbnail,
                    ),
                )
            }
        }
        // Keep the offline request keyed by the immutable video ID, matching OpenTune and
        // avoiding URL-shaped cache keys that can fail to resolve after a refresh.
        val request = DownloadRequest.Builder(downloadId, downloadId.toUri())
            .setData(song.title.toByteArray(Charsets.UTF_8))
            .setCustomCacheKey(downloadId)
            .build()
        DownloadService.sendAddDownload(
            context,
            MusicExoDownloadService::class.java,
            request,
            false,
        )
    }

    private const val FOREGROUND_RESOLVE_HEAD_START_MS = 1_200L


    fun removeDownload(context: Context, song: YtmSong) {
        DownloadService.sendRemoveDownload(
            context,
            MusicExoDownloadService::class.java,
            YtMusicDownloadUtil.downloadId(song.videoId),
            false,
        )
        // Remove a pre-migration request if one remains in the old URL-keyed cache.
        DownloadService.sendRemoveDownload(
            context,
            MusicExoDownloadService::class.java,
            watchUrl(song.videoId),
            false,
        )
    }


    fun isDownloaded(context: Context, song: YtmSong): Boolean {
        val url = watchUrl(song.videoId)
        if (YtMusicDownloadUtil.isDownloaded(song.videoId)) return true
        val legacyFile = com.streamcloud.app.data.downloads.MusicDownloader.isDownloaded(context, url)
        return legacyFile
    }
}
