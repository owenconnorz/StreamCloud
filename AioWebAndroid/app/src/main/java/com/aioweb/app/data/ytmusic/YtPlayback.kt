package com.aioweb.app.data.ytmusic

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aioweb.app.audio.MusicController
import com.aioweb.app.data.library.LibraryDb
import com.aioweb.app.data.library.TrackEntity
import com.aioweb.app.data.newpipe.NewPipeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hand-off between "user tapped a YT Music item on Library / home feed" and
 * actual audio coming out of the foreground [MusicPlaybackService].
 *
 * Offline-first: if a [TrackEntity.localPath] exists for the song, we play the
 * cached file directly (Metrolist parity) and skip NewPipe entirely.
 *
 * All `MediaController` interactions are dispatched to the Main thread because
 * Media3 requires it (silent no-op otherwise).
 */
object YtPlayback {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

    /**
     * Resolve a playable URI for [song] — prefers the downloaded local file when
     * present, otherwise resolves an HTTPS audio stream via NewPipe. Also
     * upserts the corresponding [TrackEntity] so the Library tab stays in sync.
     *
     * Must be called off the Main thread.
     *
     * Speed note: Room read/write is done **after** the playable URI is in
     * hand, so the dao round-trip never blocks the user's tap → audio gap.
     */
    private suspend fun resolvePlayable(
        context: Context,
        song: YtmSong,
        bumpPlayCount: Boolean,
    ): Pair<MediaItem, TrackEntity?> = withContext(Dispatchers.IO) {
        val url = watchUrl(song.videoId)
        val dao = LibraryDb.get(context).tracks()
        val cached = dao.byUrl(url)
        val playableUri: String = cached?.localPath?.takeIf { java.io.File(it).exists() }
            ?: NewPipeRepository.resolveAudioStream(url)

        val item = MediaItem.Builder()
            .setMediaId(url)
            .setUri(playableUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.thumbnail?.let(Uri::parse))
                    .build(),
            )
            .build()

        // Fire the Room upsert in the background so the user-perceived "tap →
        // audio" latency only includes the network resolution, not the DB
        // write. Library tab still reflects the change within a frame or two.
        backgroundScope.launch {
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

        item to cached
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
        val (item, _) = resolvePlayable(context, song, bumpPlayCount = true)
        withContext(Dispatchers.Main) {
            val controller = MusicController.get(context.applicationContext)
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }
        if (withAutoRadio) startAutoRadio(context, song)
    }

    /**
     * Build a Metrolist-style endless queue for [seed] in the background. We
     * fetch up to 20 related tracks via [EndlessPlayback], resolve each to a
     * [MediaItem] lazily (cached file → NewPipe fallback), and stream them
     * into the player one at a time so the user doesn't wait on the whole
     * batch before skip/prev light up.
     */
    private fun startAutoRadio(context: Context, seed: YtmSong) {
        backgroundScope.launch {
            runCatching {
                val related = EndlessPlayback.relatedSongs(context, seed.videoId)
                if (related.isEmpty()) return@runCatching
                related.forEach { s ->
                    runCatching {
                        val (item, _) = resolvePlayable(context, s, bumpPlayCount = false)
                        withContext(Dispatchers.Main) {
                            val controller = MusicController.get(context.applicationContext)
                            // Only append if the user hasn't already kicked
                            // off a different track in the meantime.
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
     * Used by the in-player "Radio" action so the user can spin a Metrolist-
     * style auto-radio off whatever's currently playing.
     */
    fun startRadioFromCurrent(context: Context, mediaIdUrl: String) {
        val videoId = mediaIdUrl
            .substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?: return
        // Minimal stub song — only the videoId matters for radio seeding.
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
        val (item, _) = resolvePlayable(context, song, bumpPlayCount = false)
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
        val (item, _) = resolvePlayable(context, song, bumpPlayCount = false)
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
     */
    suspend fun playPlaylist(context: Context, songs: List<YtmSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        // Kick off the start song synchronously (resolves its stream) so the
        // user hears audio ASAP. Skip the auto-radio — the playlist's OWN
        // remaining tracks fill the queue.
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        playSong(context, songs[safeStart], withAutoRadio = false)
        // Queue the remaining tracks behind it without blocking playback. We
        // resolve each lazily; Media3 handles async prepare once it reaches them.
        val queue = buildList {
            addAll(songs.subList(safeStart + 1, songs.size))
            addAll(songs.subList(0, safeStart))
        }
        if (queue.isEmpty()) return
        withContext(Dispatchers.IO) {
            queue.forEach { s ->
                runCatching {
                    val (item, _) = resolvePlayable(context, s, bumpPlayCount = false)
                    withContext(Dispatchers.Main) {
                        MusicController.get(context.applicationContext).addMediaItem(item)
                    }
                }
            }
        }
    }

    /** Download a YT Music song — delegates to [MusicDownloader] with a watch URL. */
    suspend fun downloadSong(context: Context, song: YtmSong): java.io.File {
        val url = watchUrl(song.videoId)
        // Seed the TrackEntity so the downloader's `dao.setLocalPath(...)` has a
        // row to update (otherwise REPLACE recreates it with empty metadata).
        LibraryDb.get(context).tracks().upsert(
            TrackEntity(
                url = url,
                title = song.title,
                artist = song.artist,
                durationSec = song.durationSeconds ?: 0L,
                thumbnail = song.thumbnail,
            ),
        )
        return com.aioweb.app.data.downloads.MusicDownloader.download(
            context, url, song.title,
        )
    }

    /** Remove a previously-downloaded song from disk and clear `local_path`. */
    suspend fun removeDownload(context: Context, song: YtmSong) {
        com.aioweb.app.data.downloads.MusicDownloader.delete(
            context, watchUrl(song.videoId),
        )
    }

    fun isDownloaded(context: Context, song: YtmSong): Boolean =
        com.aioweb.app.data.downloads.MusicDownloader.isDownloaded(
            context,
            watchUrl(song.videoId),
        )
}
