package com.streamcloud.app.data.sonos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamcloud.app.MainActivity
import com.streamcloud.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps the phone audio proxy reachable while Sonos plays in the background. */
class SonosCastForegroundService : Service() {
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var volumeSession: MediaSession? = null
    private var sonosVolumeProvider: VolumeProvider? = null

    override fun onCreate() {
        super.onCreate()
        createVolumeSession()
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
        volumeSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Playing on Sonos")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, speaker)
                .build(),
        )
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
        serviceScope.cancel()
        volumeSession?.isActive = false
        volumeSession?.release()
        volumeSession = null
        sonosVolumeProvider = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Keep a remote-volume media session alive in the same foreground service as the Sonos proxy.
     * The app's music session can be suspended by Android while its local player is paused for
     * casting; this session ensures hardware volume keys still reach the Sonos speaker.
     */
    private fun createVolumeSession() {
        val provider = object : VolumeProvider(
            VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
            MAX_VOLUME,
            SonosRepository.sonosVolume.value.coerceIn(0, MAX_VOLUME),
        ) {
            override fun onSetVolumeTo(volume: Int) {
                SonosRepository.setVolume(volume)
            }

            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    AudioManager.ADJUST_RAISE -> SonosRepository.adjustVolume(VOLUME_STEP)
                    AudioManager.ADJUST_LOWER -> SonosRepository.adjustVolume(-VOLUME_STEP)
                    AudioManager.ADJUST_MUTE -> SonosRepository.setVolume(0)
                    AudioManager.ADJUST_UNMUTE -> {
                        if (SonosRepository.sonosVolume.value == 0) {
                            SonosRepository.setVolume(DEFAULT_UNMUTE_VOLUME)
                        }
                    }
                    AudioManager.ADJUST_TOGGLE_MUTE -> {
                        if (SonosRepository.sonosVolume.value == 0) {
                            SonosRepository.setVolume(DEFAULT_UNMUTE_VOLUME)
                        } else {
                            SonosRepository.setVolume(0)
                        }
                    }
                }
            }
        }
        sonosVolumeProvider = provider

        volumeSession = MediaSession(this, "$packageName:SonosVolume").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS,
            )
            setPlaybackToRemote(provider)
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .setActions(0L)
                    .build(),
            )
            isActive = true
        }
        serviceScope.launch {
            SonosRepository.sonosVolume.collect { volume ->
                provider.setCurrentVolume(volume.coerceIn(0, MAX_VOLUME))
            }
        }
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
        private const val MAX_VOLUME = 100
        private const val VOLUME_STEP = 5
        private const val DEFAULT_UNMUTE_VOLUME = 25
        fun start(context: Context, speakerName: String) {
            context.startForegroundService(Intent(context, SonosCastForegroundService::class.java).putExtra(EXTRA_SPEAKER_NAME, speakerName))
        }
        fun stop(context: Context) { context.stopService(Intent(context, SonosCastForegroundService::class.java)) }
    }
}
