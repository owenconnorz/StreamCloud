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

/**
 * Hand-off between "user tapped a YT Music item on Library / home feed" and
 * actual audio coming out of the foreground [MusicPlaybackService].
 *
 * ── Stream resolution ────────────────────────────────────────────────────
 * MediaItems are now built with `uri = watchUrl` and `customCacheKey = watchUrl`
 * and handed directly to ExoPlayer. Resolution to the actual audio stream is
 * deferred to the [ResolvingDataSource.Factory] inside [MusicPlaybackService]:
 *
 *   1. downloadCache hit (Metrolist ExoDownload) → serve bytes from cache
 *   2. playerCache hit (LRU ephemeral buffer)   → serve bytes from cache
 *   3. localPath in Room (legacy OkHttp downloads) → play local file
 *   4. NewPipe resolves live stream URL            → streams + writes to playerCache
 *
 * This matches Metrolist's architecture exactly, and also fixes the previously
 * broken LRU cache (which was keyed on the expiring stream URL, not the stable
 * watch URL).
 *
 * ── Downloads ────────────────────────────────────────────────────────────
 * [downloadSong] now delegates to Media3's [DownloadService] (via
 * [MusicExoDownloadService]) rather than the OkHttp-based [MusicDownloader].
 * Downloads survive process death and can be monitored via [YtMusicDownloadUtil.downloads].
 */
@OptIn(UnstableApi::class)
object YtPlayback {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

    /**
     * Build a [MediaItem] for [song] and upsert its [TrackEntity] to Room.
     *
     * The URI is set to the watch URL.  Resolution to the playable audio stream
     * (downloadCache → playerCache → localPath → NewPipe) is handled lazily by
     * [MusicPlaybackService.buildDataSourceFactory] when ExoPlayer first requests bytes.
     */
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

    /**
     * Upsert a [TrackEntity] for [song] in Room so the Library tab stays in sync.
     * This runs in the background and does NOT block playback.
     */
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

    /**
     * Replace current playback with [song].
     *
     * @param withAutoRadio when true (default) we follow up with a Metrolist-
     *  style auto-radio (~20 related tracks) so skip/prev have somewhere to
     *  go. Pass `false` from playlist context — the playlist's own remaining
     *  tracks form the queue and we don't want random radio interleaved.
     */
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

    /**
     * Build a Metrolist-style endless queue for [seed] in the background.
     */
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

    /**
     * Public wrapper around [startAutoRadio] driven by a media-id URL (the
     * `https://music.youtube.com/watch?v=…` string we stash on every MediaItem).
     */
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

    /** Insert [song] right after the currently-playing track. */
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

    /** Append [song] to the end of the playback queue. */
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

    /**
     * Queue a full playlist into the player. The [startIndex] song plays
     * immediately, the rest become the upcoming queue.
     *
     * Uses [Player.setMediaItems] with an explicit start index so ExoPlayer
     * receives all items in a **single IPC call** rather than N sequential
     * `addMediaItem` calls.  For a 380-track playlist this is the difference
     * between one round-trip and 379 — far faster, and it eliminates the
     * [MusicController.get] race that caused "song doesn't auto-play".
     */
    suspend fun playPlaylist(context: Context, songs: List<YtmSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)

        // Build MediaItems on the caller's thread (pure, no I/O)
        val allItems = songs.map { buildMediaItem(it) }

        // Bump play count for the tapped track; background-upsert the rest
        upsertTrack(context, songs[safeStart], bumpPlayCount = true)
        songs.indices.filter { it != safeStart }.forEach { i ->
            upsertTrack(context, songs[i], bumpPlayCount = false)
        }

        // Single IPC round-trip — ExoPlayer handles the start index
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.setMediaItems(allItems, safeStart, /* startPositionMs= */ 0L)
            controller.prepare()
            controller.play()
        }
    }

    /**
     * Enqueue a download for [song] via Media3's [DownloadService].
     *
     * This replaces the old OkHttp-based [MusicDownloader.download] approach.
     * The download runs in [MusicExoDownloadService] — a foreground service that
     * survives process death — and stores bytes in [DownloadCaches.downloadCache]
     * under the watch URL as the cache key.
     *
     * Progress can be observed via [YtMusicDownloadUtil.getDownload].
     */
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

    /** Cancel and remove a pending or completed download. */
    fun removeDownload(context: Context, song: YtmSong) {
        DownloadService.sendRemoveDownload(
            context,
            MusicExoDownloadService::class.java,
            watchUrl(song.videoId),
            false,
        )
    }

    /**
     * Returns true if [song] has been fully downloaded into [DownloadCaches.downloadCache]
     * OR if a legacy OkHttp `.m4a` file exists on disk for it.
     */
    fun isDownloaded(context: Context, song: YtmSong): Boolean {
        val url = watchUrl(song.videoId)
        if (YtMusicDownloadUtil.isDownloaded(url)) return true
        val legacyFile = com.streamcloud.app.data.downloads.MusicDownloader.isDownloaded(context, url)
        return legacyFile
    }
}
