package com.streamcloud.app.data.downloads

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import com.streamcloud.app.data.library.LibraryDb
import com.streamcloud.app.data.library.MusicDownloadOwnerEntity
import com.streamcloud.app.data.library.MusicPlaylistDownloadStateEntity
import com.streamcloud.app.data.ytmusic.YtPlayback
import com.streamcloud.app.data.ytmusic.YtmSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class PlaylistDownloadSummary(
    val queued: Int = 0,
    val alreadyAvailable: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
)

object PlaylistMusicDownloadManager {
    private val playlistLocks = ConcurrentHashMap<String, Mutex>()
    private val videoLocks = ConcurrentHashMap<String, Mutex>()

    fun playlistOwnerKey(provider: String, playlistId: String): String =
        "playlist:" + provider.trim().lowercase() + ":" + playlistId.trim()

    fun observeEnabled(context: Context, ownerKey: String): Flow<Boolean> =
        LibraryDb.get(context.applicationContext).musicPlaylistDownloadStates().isEnabled(ownerKey)

    suspend fun setEnabled(
        context: Context,
        ownerKey: String,
        enabled: Boolean,
        songs: List<YtmSong>,
        pruneMissing: Boolean = true,
    ): PlaylistDownloadSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = LibraryDb.get(appContext)
        playlistLock(ownerKey).withLock {
            if (enabled) {
                db.musicPlaylistDownloadStates().set(
                    MusicPlaylistDownloadStateEntity(
                        playlistKey = ownerKey,
                        enabled = true,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                syncLocked(appContext, ownerKey, songs, pruneMissing)
            } else {
                claimUnownedAvailableTracks(appContext, ownerKey, songs)
                db.musicPlaylistDownloadStates().remove(ownerKey)
                PlaylistDownloadSummary(removed = releaseAllLocked(appContext, ownerKey))
            }
        }
    }

    suspend fun syncEnabledPlaylist(
        context: Context,
        ownerKey: String,
        songs: List<YtmSong>,
        pruneMissing: Boolean = true,
    ): PlaylistDownloadSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = LibraryDb.get(appContext)
        playlistLock(ownerKey).withLock {
            if (db.musicPlaylistDownloadStates().enabled(ownerKey) != true) {
                PlaylistDownloadSummary()
            } else {
                syncLocked(appContext, ownerKey, songs, pruneMissing)
            }
        }
    }

    suspend fun cleanupDisabledPlaylist(context: Context, ownerKey: String) =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val db = LibraryDb.get(appContext)
            playlistLock(ownerKey).withLock {
                if (db.musicPlaylistDownloadStates().enabled(ownerKey) != true) {
                    releaseAllLocked(appContext, ownerKey)
                }
                Unit
            }
        }

    suspend fun releaseTrack(context: Context, ownerKey: String, videoId: String) =
        withContext(Dispatchers.IO) {
            releaseOne(context.applicationContext, ownerKey, videoId)
        }

    suspend fun addManualDownload(context: Context, song: YtmSong) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        withVideoLock(song.videoId) {
            LibraryDb.get(appContext).musicDownloadOwners().add(
                MusicDownloadOwnerEntity(MusicDownloadOwnerEntity.MANUAL_OWNER_KEY, song.videoId),
            )
            YtPlayback.enqueueDownload(appContext, song)
        }
    }

    suspend fun removeManualDownload(context: Context, videoId: String) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        withVideoLock(videoId) {
            val owners = LibraryDb.get(appContext).musicDownloadOwners().ownersForVideo(videoId)
            when {
                MusicDownloadOwnerEntity.MANUAL_OWNER_KEY in owners -> {
                    releaseOneLocked(
                        appContext,
                        MusicDownloadOwnerEntity.MANUAL_OWNER_KEY,
                        videoId,
                    )
                }
                owners.isEmpty() -> YtPlayback.removeDownloadFiles(appContext, videoId)
            }
            Unit
        }
    }

    private suspend fun syncLocked(
        context: Context,
        ownerKey: String,
        songs: List<YtmSong>,
        pruneMissing: Boolean,
    ): PlaylistDownloadSummary {
        YtMusicDownloadUtil.downloadManager(context)
        val ownerDao = LibraryDb.get(context).musicDownloadOwners()
        val distinctSongs = songs.distinctBy(YtmSong::videoId)
        val desiredIds = distinctSongs.mapTo(mutableSetOf(), YtmSong::videoId)
        var queued = 0
        var alreadyAvailable = 0
        var removed = 0
        var failed = 0

        if (pruneMissing) {
            val staleIds = ownerDao.videoIdsForOwner(ownerKey).filterNot(desiredIds::contains)
            for (videoId in staleIds) {
                if (releaseOne(context, ownerKey, videoId)) removed++
            }
        }

        for (song in distinctSongs) {
            withVideoLock(song.videoId) {
                val downloaded = YtPlayback.isDownloaded(context, song)
                val activeRequest = hasExistingRequest(song.videoId)
                // Adopt an available, unowned copy into this playlist. Any known manual or
                // other-playlist owners remain recorded and continue to protect the shared file.
                ownerDao.add(MusicDownloadOwnerEntity(ownerKey, song.videoId))
                if (downloaded || activeRequest) {
                    alreadyAvailable++
                } else {
                    try {
                        YtPlayback.enqueueDownload(context, song)
                        queued++
                    } catch (_: Throwable) {
                        ownerDao.remove(ownerKey, song.videoId)
                        failed++
                    }
                }
            }
        }
        return PlaylistDownloadSummary(queued, alreadyAvailable, removed, failed)
    }

    private suspend fun claimUnownedAvailableTracks(
        context: Context,
        ownerKey: String,
        songs: List<YtmSong>,
    ) {
        YtMusicDownloadUtil.downloadManager(context)
        val ownerDao = LibraryDb.get(context).musicDownloadOwners()
        for (song in songs.distinctBy(YtmSong::videoId)) {
            withVideoLock(song.videoId) {
                val owners = ownerDao.ownersForVideo(song.videoId)
                if (ownerKey !in owners &&
                    (YtPlayback.isDownloaded(context, song) || hasExistingRequest(song.videoId))
                ) {
                    ownerDao.add(MusicDownloadOwnerEntity(ownerKey, song.videoId))
                }
            }
        }
    }

    private suspend fun releaseAllLocked(context: Context, ownerKey: String): Int {
        val ownerDao = LibraryDb.get(context).musicDownloadOwners()
        val videoIds = ownerDao.videoIdsForOwner(ownerKey)
        var removed = 0
        for (videoId in videoIds) {
            if (releaseOne(context, ownerKey, videoId)) removed++
        }
        return removed
    }

    private suspend fun releaseOne(context: Context, ownerKey: String, videoId: String): Boolean =
        withVideoLock(videoId) {
            releaseOneLocked(context, ownerKey, videoId)
        }

    private suspend fun releaseOneLocked(
        context: Context,
        ownerKey: String,
        videoId: String,
    ): Boolean {
        val ownerDao = LibraryDb.get(context).musicDownloadOwners()
        val owners = ownerDao.ownersForVideo(videoId)
        if (ownerKey !in owners) return false

        if (owners.size > 1) {
            ownerDao.remove(ownerKey, videoId)
            return false
        }

        // Keep the final owner until file cleanup succeeds so a failed deletion can be retried.
        YtPlayback.removeDownloadFiles(context, videoId)
        ownerDao.remove(ownerKey, videoId)
        return true
    }

    private fun hasExistingRequest(videoId: String): Boolean {
        val watchUrl = YtPlayback.watchUrl(videoId)
        val download = YtMusicDownloadUtil.downloads.value[videoId]
            ?: YtMusicDownloadUtil.downloads.value[watchUrl]
        val media3RequestExists = download?.state?.let {
            it != Download.STATE_FAILED && it != Download.STATE_REMOVING
        } == true
        return media3RequestExists || MusicDownloader.isDownloading(watchUrl)
    }

    private fun playlistLock(ownerKey: String): Mutex =
        playlistLocks.computeIfAbsent(ownerKey) { Mutex() }

    private suspend fun <T> withVideoLock(videoId: String, action: suspend () -> T): T =
        videoLocks.computeIfAbsent(videoId) { Mutex() }.withLock { action() }
}
