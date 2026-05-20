package com.streamcloud.app.data.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * Foreground service that drives all music downloads via Media3's [DownloadManager].
 *
 * Mirrors Metrolist's ExoDownloadService. Key behaviours:
 *  - Downloads continue even when the app is in the background or the process is killed,
 *    because [DownloadManager] persists its queue to a SQLite database.
 *  - Up to 3 parallel downloads (configured in [YtMusicDownloadUtil]).
 *  - Shows an ongoing notification with per-download progress and a cancel button.
 *
 * The service is started automatically by [DownloadService.sendAddDownload]; callers
 * only need to send the request via [YtPlayback.downloadSong].
 */
@OptIn(UnstableApi::class)
class MusicExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    com.streamcloud.app.R.string.downloading,
    0,
) {

    override fun getDownloadManager(): DownloadManager =
        YtMusicDownloadUtil.downloadManager(this)

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            downloadManager.currentDownloads.forEach { download ->
                downloadManager.removeDownload(download.request.id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        // contentIntent opens the app when the user taps the notification body.
        // Previously cancelIntent was passed here — tapping the notification was
        // immediately cancelling all downloads.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.streamcloud.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return YtMusicDownloadUtil.downloadNotificationHelper(this)
            .buildProgressNotification(
                this,
                android.R.drawable.stat_sys_download,
                contentIntent,
                when {
                    downloads.size == 1 -> Util.fromUtf8Bytes(downloads[0].request.data)
                    else -> "${downloads.size} songs downloading"
                },
                downloads,
                notMetRequirements,
            )
    }

    companion object {
        const val CHANNEL_ID = "music_exo_downloads"
        const val NOTIFICATION_ID = 3
        const val JOB_ID = 2
        const val ACTION_CANCEL_ALL = "CANCEL_ALL_DOWNLOADS"
    }
}
