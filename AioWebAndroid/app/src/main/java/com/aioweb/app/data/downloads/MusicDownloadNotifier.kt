package com.aioweb.app.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Single notification channel for music download progress. Each in-flight
 * download owns a stable [Int] id (derived from URL hash) so the same row
 * updates as the download progresses, then auto-dismisses on completion.
 *
 * Required for Metrolist parity: the user expects to see download progress in
 * the system shade and a final "X downloaded" confirmation.
 */
internal object MusicDownloadNotifier {
    private const val CHANNEL_ID = "music_downloads"
    private const val CHANNEL_NAME = "Music downloads"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress + completion of song downloads"
                    setShowBadge(false)
                },
            )
        }
    }

    /** Stable per-URL id so the OS replaces the same notification slot. */
    fun idFor(url: String): Int = (url.hashCode() and 0x7fffffff)

    /**
     * Post or update an "ongoing" progress notification. [fraction] in 0..1, or
     * null for indeterminate (during stream resolution before the byte stream
     * starts).
     */
    fun postProgress(
        context: Context,
        url: String,
        title: String,
        fraction: Float?,
    ) {
        runCatching {
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading $title")
                .setContentText(
                    if (fraction != null) "${(fraction * 100).toInt()}%"
                    else "Resolving stream…",
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (fraction != null) {
                builder.setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            } else {
                builder.setProgress(0, 0, true)
            }

            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            nm?.notify(idFor(url), builder.build())
        }
    }

    /** Post a brief completion notification. Auto-dismisses after a few seconds. */
    fun postComplete(context: Context, url: String, title: String) {
        runCatching {
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Downloaded")
                .setContentText(title)
                .setAutoCancel(true)
                .setTimeoutAfter(4000)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            nm?.notify(idFor(url), builder.build())
        }
    }

    fun cancel(context: Context, url: String) {
        runCatching {
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            nm?.cancel(idFor(url))
        }
    }
}
