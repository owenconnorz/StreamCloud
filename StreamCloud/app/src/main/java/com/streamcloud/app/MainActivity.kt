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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {  }


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

        setTheme(R.style.Theme_StreamCloud)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()


        com.streamcloud.app.cast.initCast(applicationContext)


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
