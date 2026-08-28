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
import com.streamcloud.app.data.downloads.MusicExoDownloadService
import com.streamcloud.app.data.downloads.YtMusicDownloadUtil
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
object YtPlayback {

    const val EXTRA_VIDEO_ID = "videoId"
    const val EXTRA_WATCH_URL = "watchUrl"
    const val EXTRA_IS_MUSIC_VIDEO = "isMusicVideo"
    const val EXTRA_PLAYBACK_ATTEMPT_ID = "playbackAttemptId"

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"


    private fun buildMediaItem(song: YtmSong, playbackAttemptId: Long? = null): MediaItem {
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
                        playbackAttemptId?.let { putLong(EXTRA_PLAYBACK_ATTEMPT_ID, it) }
                    })
                    .build(),
            )
            .build()
    }

    private fun primeStreams(songs: Iterable<YtmSong>) {
        YtMusicStreamResolver.prime(
            songs.map(YtmSong::videoId)
                .filterNot(YtMusicDownloadUtil::isDownloaded),
        )
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
        val playbackAttempt = PlaybackLatencyTrace.begin(song.videoId)
        // This is foreground work, not list prefetch. It shares its result with Media3 instead of
        // being cancelled and restarted when the data source promotes the selected item.
        if (!YtMusicDownloadUtil.isDownloaded(song.videoId)) {
            YtMusicStreamResolver.primeForPlayback(song.videoId)
        }
        val item = buildMediaItem(song, playbackAttempt.attemptId)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            PlaybackLatencyTrace.mark(song.videoId, "controller-ready")
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
            PlaybackLatencyTrace.mark(song.videoId, "prepare-play")
        }
        backgroundScope.launch {
            if (PlaybackLatencyTrace.awaitFirstAudio(playbackAttempt)) {
                upsertTrack(context, song, bumpPlayCount = true)
            }
        }
        if (withAutoRadio) startAutoRadio(context, song, playbackAttempt)
    }


    private fun startAutoRadio(
        context: Context,
        seed: YtmSong,
        playbackAttempt: PlaybackLatencyTrace.Handle?,
    ) {
        backgroundScope.launch {
            runCatching {
                if (playbackAttempt != null && !PlaybackLatencyTrace.awaitFirstAudio(playbackAttempt)) {
                    return@runCatching
                }
                val related = EndlessPlayback.relatedSongs(context, seed.videoId)
                if (related.isEmpty()) return@runCatching
                primeStreams(related.take(1))
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
        startAutoRadio(context, seed, playbackAttempt = null)
    }


    suspend fun playNext(context: Context, song: YtmSong) {
        val item = buildMediaItem(song)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            if (controller.mediaItemCount == 0) {
                if (!YtMusicDownloadUtil.isDownloaded(song.videoId)) {
                    YtMusicStreamResolver.primeForPlayback(song.videoId)
                }
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            } else {
                val insertAt = (controller.currentMediaItemIndex + 1)
                    .coerceIn(0, controller.mediaItemCount)
                controller.addMediaItem(insertAt, item)
                if (!YtMusicDownloadUtil.isDownloaded(song.videoId)) {
                    YtMusicStreamResolver.primeForPlayback(song.videoId)
                }
            }
        }
    }


    suspend fun addToQueue(context: Context, song: YtmSong) {
        val item = buildMediaItem(song)
        val startsPlayback = withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            if (controller.mediaItemCount == 0) {
                if (!YtMusicDownloadUtil.isDownloaded(song.videoId)) {
                    YtMusicStreamResolver.primeForPlayback(song.videoId)
                }
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
                true
            } else {
                controller.addMediaItem(item)
                false
            }
        }
        if (!startsPlayback) {
            primeStreams(listOf(song))
        }
    }


    suspend fun playPlaylist(context: Context, songs: List<YtmSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        val activeSong = songs[safeStart]
        val playbackAttempt = PlaybackLatencyTrace.begin(activeSong.videoId)
        val foregroundResolution = if (YtMusicDownloadUtil.isDownloaded(activeSong.videoId)) {
            null
        } else {
            YtMusicStreamResolver.primeForPlayback(activeSong.videoId)
        }


        val allItems = songs.mapIndexed { index, song ->
            buildMediaItem(
                song = song,
                playbackAttemptId = playbackAttempt.attemptId.takeIf { index == safeStart },
            )
        }


        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            PlaybackLatencyTrace.mark(activeSong.videoId, "controller-ready")
            controller.setMediaItems(allItems, safeStart,  0L)
            controller.prepare()
            controller.play()
            PlaybackLatencyTrace.mark(activeSong.videoId, "prepare-play")
        }
        backgroundScope.launch {
            if (foregroundResolution?.await()?.isSuccess != false) {
                songs.getOrNull(safeStart + 1)?.let { nextSong ->
                    if (!YtMusicDownloadUtil.isDownloaded(nextSong.videoId)) {
                        YtMusicStreamResolver.primeForPlayback(nextSong.videoId)
                    }
                }
            }
        }
        backgroundScope.launch {
            if (!PlaybackLatencyTrace.awaitFirstAudio(playbackAttempt)) return@launch
            upsertTrack(context, songs[safeStart], bumpPlayCount = true)
            songs.indices.filter { it != safeStart }.forEach { i ->
                upsertTrack(context, songs[i], bumpPlayCount = false)
            }
        }
    }


    fun downloadSong(context: Context, song: YtmSong) {
        val watchUrl = watchUrl(song.videoId)
        val downloadId = YtMusicDownloadUtil.downloadId(song.videoId)
        // Resolve immediately while Android starts the foreground download service. The download
        // data source consumes this same shared job/cache instead of starting from a cold resolver.
        YtMusicStreamResolver.primeForPlayback(song.videoId)
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
