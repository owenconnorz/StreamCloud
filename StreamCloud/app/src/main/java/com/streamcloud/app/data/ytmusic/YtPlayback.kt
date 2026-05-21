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
                        putString("videoId", song.videoId)
                        putString("watchUrl", url)
                    })
                    .build(),
            )
            .build()
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
        val item = buildMediaItem(song)
        upsertTrack(context, song, bumpPlayCount = true)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }
        if (withAutoRadio) startAutoRadio(context, song)
    }


    private fun startAutoRadio(context: Context, seed: YtmSong) {
        backgroundScope.launch {
            runCatching {
                val related = EndlessPlayback.relatedSongs(context, seed.videoId)
                if (related.isEmpty()) return@runCatching
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
        startAutoRadio(context, seed)
    }


    suspend fun playNext(context: Context, song: YtmSong) {
        val item = buildMediaItem(song)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            if (controller.mediaItemCount == 0) {
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            } else {
                val insertAt = (controller.currentMediaItemIndex + 1)
                    .coerceIn(0, controller.mediaItemCount)
                controller.addMediaItem(insertAt, item)
            }
        }
    }


    suspend fun addToQueue(context: Context, song: YtmSong) {
        val item = buildMediaItem(song)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            if (controller.mediaItemCount == 0) {
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            } else {
                controller.addMediaItem(item)
            }
        }
    }


    suspend fun playPlaylist(context: Context, songs: List<YtmSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)


        val allItems = songs.map { buildMediaItem(it) }


        upsertTrack(context, songs[safeStart], bumpPlayCount = true)
        songs.indices.filter { it != safeStart }.forEach { i ->
            upsertTrack(context, songs[i], bumpPlayCount = false)
        }


        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.setMediaItems(allItems, safeStart,  0L)
            controller.prepare()
            controller.play()
        }
    }


    fun downloadSong(context: Context, song: YtmSong) {
        val url = watchUrl(song.videoId)
        backgroundScope.launch {
            val dao = LibraryDb.get(context).tracks()
            val existing = runCatching { dao.byUrl(url) }.getOrNull()
            if (existing == null) {
                dao.upsert(
                    TrackEntity(
                        url = url,
                        title = song.title,
                        artist = song.artist,
                        durationSec = song.durationSeconds ?: 0L,
                        thumbnail = song.thumbnail,
                    ),
                )
            }
        }
        val request = DownloadRequest.Builder(url, url.toUri())
            .setData(song.title.toByteArray(Charsets.UTF_8))
            .setCustomCacheKey(url)
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
            watchUrl(song.videoId),
            false,
        )
    }


    fun isDownloaded(context: Context, song: YtmSong): Boolean {
        val url = watchUrl(song.videoId)
        if (YtMusicDownloadUtil.isDownloaded(url)) return true
        val legacyFile = com.streamcloud.app.data.downloads.MusicDownloader.isDownloaded(context, url)
        return legacyFile
    }
}
