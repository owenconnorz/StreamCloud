package com.streamcloud.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.ui.StreamCloudApp
import com.streamcloud.app.ui.theme.StreamCloudTheme
import java.io.File

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

        showPreviousCrashIfAny()

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

    private fun showPreviousCrashIfAny() {
        val crashFile = File(filesDir, StreamCloudApplication.CRASH_FILE)
        if (!crashFile.exists()) return
        val report = runCatching { crashFile.readText() }.getOrNull() ?: return
        crashFile.delete()

        runOnUiThread {
            runCatching {
                AlertDialog.Builder(this, R.style.Theme_StreamCloud_AppCompat)
                    .setTitle("Previous crash detected")
                    .setMessage(report.take(2000))
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
    }
}
