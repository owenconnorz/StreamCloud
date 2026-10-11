package com.streamcloud.app.data.sonos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamcloud.app.MainActivity
import com.streamcloud.app.R

/** Keeps the phone audio proxy reachable while Sonos plays in the background. */
class SonosCastForegroundService : Service() {
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sonos casting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Sonos audio casting connected in the background"
                setShowBadge(false)
            })
        }
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speaker = intent?.getStringExtra(EXTRA_SPEAKER_NAME).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(speaker))
        if (cpuWakeLock?.isHeld != true) cpuWakeLock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SonosCast").apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "CPU keep-alive unavailable", it) }.getOrNull()
        if (wifiLock?.isHeld != true) wifiLock = runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:SonosCast").apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "Wi-Fi keep-alive unavailable", it) }.getOrNull()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(speaker: String): Notification {
        val tapIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Playing on Sonos")
            .setContentText(if (speaker.isBlank()) "Keeping the audio connection active" else "Casting to $speaker")
            .setContentIntent(tapIntent)
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    companion object {
        private const val TAG = "SonosCastService"
        private const val CHANNEL_ID = "sc_sonos_cast"
        private const val NOTIFICATION_ID = 2002
        private const val EXTRA_SPEAKER_NAME = "speaker_name"
        fun start(context: Context, speakerName: String) {
            context.startForegroundService(Intent(context, SonosCastForegroundService::class.java).putExtra(EXTRA_SPEAKER_NAME, speakerName))
        }
        fun stop(context: Context) { context.stopService(Intent(context, SonosCastForegroundService::class.java)) }
    }
}
