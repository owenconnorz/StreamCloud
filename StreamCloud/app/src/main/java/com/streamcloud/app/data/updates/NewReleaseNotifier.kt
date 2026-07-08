package com.streamcloud.app.data.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal object NewReleaseNotifier {

    private const val CHANNEL_ID   = "artist_new_releases"
    private const val CHANNEL_NAME = "New Artist Releases"

    data class NewRelease(val artistName: String, val releaseTitle: String, val releaseType: String)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when a followed artist releases new music"
            },
        )
    }

    fun notify(context: Context, releases: List<NewRelease>) {
        if (releases.isEmpty()) return
        runCatching {
            ensureChannel(context)
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

            for (release in releases) {
                val title = "${release.artistName} — New ${release.releaseType}"
                val body  = release.releaseTitle
                val notifId = (release.artistName + release.releaseTitle).hashCode() and 0x7fffffff
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                nm.notify(notifId, builder.build())
            }
        }
    }
}
