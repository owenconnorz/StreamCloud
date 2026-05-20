package com.streamcloud.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.ui.StreamCloudApp
import com.streamcloud.app.ui.theme.StreamCloudTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /**
     * Intercept hardware volume keys while casting to Sonos.
     * When the cast state is [SonosRepository.CastState.Casting] the keys
     * adjust the Sonos speaker volume by ±5% instead of the phone's media volume.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            SonosRepository.castState.value is SonosRepository.CastState.Casting
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { SonosRepository.adjustVolume(5); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { SonosRepository.adjustVolume(-5); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore non-splash theme before super
        setTheme(R.style.Theme_StreamCloud)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Lazy-init the Google Cast SDK now so the cast notification surfaces
        // promptly when a route is selected from any screen.
        com.streamcloud.app.cast.initCast(applicationContext)
        // Android 13+ requires explicit POST_NOTIFICATIONS permission to surface the
        // media-session notification when music is playing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            StreamCloudTheme {
                StreamCloudApp()
            }
        }
    }
}
