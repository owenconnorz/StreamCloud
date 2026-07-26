package com.streamcloud.app.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal object MovieDownloadNotifier {

    private const val CHANNEL_ID   = "movie_downloads"
    private const val CHANNEL_NAME = "Movie Downloads"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and completion notifications for movie downloads"
                setShowBadge(false)
            },
        )
    }

    private fun notifId(tmdbId: Long): Int = ((tmdbId xor (tmdbId ushr 32)) and 0x7fffffffL).toInt() + 200_000

    fun postProgress(context: Context, tmdbId: Long, title: String, fraction: Float?) {
        runCatching {
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading")
                .setContentText(title)
                .setSubText(if (fraction != null) "${(fraction * 100).toInt()}%" else "Starting…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (fraction != null) {
                builder.setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            } else {
                builder.setProgress(0, 0, true)
            }

            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(notifId(tmdbId), builder.build())
        }
    }

    fun postComplete(context: Context, tmdbId: Long, title: String) {
        runCatching {
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(title)
                .setAutoCancel(true)
                .setTimeoutAfter(6_000)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(notifId(tmdbId), builder.build())
        }
    }

    fun cancel(context: Context, tmdbId: Long) {
        runCatching {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.cancel(notifId(tmdbId))
        }
    }
}
