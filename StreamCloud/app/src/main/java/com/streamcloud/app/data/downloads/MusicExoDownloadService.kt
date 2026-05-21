package com.streamcloud.app.data.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
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
 *  - Shows an ongoing notification with per-download progress, a content intent that
 *    opens the app, and a "Cancel all" action button to clear stuck downloads.
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
        // Tapping the notification body opens the app.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.streamcloud.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // "Cancel all" action button — sends ACTION_CANCEL_ALL to this service,
        // which calls removeDownload() on every queued/downloading item and lets
        // the DownloadManager stop the foreground service cleanly.
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicExoDownloadService::class.java)
                .setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when {
            notMetRequirements != 0 -> "Waiting for network…"
            downloads.size == 1 -> "Downloading ${Util.fromUtf8Bytes(downloads[0].request.data)}"
            else -> "${downloads.size} songs downloading"
        }

        // Compute average progress across all active downloads.
        val avgPct = if (downloads.isEmpty()) 0
            else (downloads.sumOf { it.percentDownloaded.toDouble() } / downloads.size).toInt()
            .coerceIn(0, 100)
        val indeterminate = downloads.any {
            it.state == Download.STATE_QUEUED || it.percentDownloaded == 0f
        } && avgPct == 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, avgPct, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel all",
                cancelIntent,
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "music_exo_downloads"
        const val NOTIFICATION_ID = 3
        const val JOB_ID = 2
        const val ACTION_CANCEL_ALL = "CANCEL_ALL_DOWNLOADS"
    }
}
