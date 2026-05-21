package com.streamcloud.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.streamcloud.app.MainActivity
import com.streamcloud.app.R

class VideoPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
        startForeground(NOTIF_ID, buildNotification(this, title))
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {

        private const val CHANNEL_ID  = "sc_video_playback"
        private const val NOTIF_ID    = 2001
        private const val EXTRA_TITLE = "title"


        fun start(context: Context, title: String) {
            val intent = Intent(context, VideoPlaybackService::class.java)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }


        fun stop(context: Context) {
            context.stopService(Intent(context, VideoPlaybackService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Video Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps video streaming alive in the background"
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context, title: String): Notification {
            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText("Tap to return to video")
                .setContentIntent(tapIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
