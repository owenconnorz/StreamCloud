package com.streamcloud.app

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.streamcloud.app.data.sonos.SonosRepository
import com.streamcloud.app.ui.StreamCloudApp
import com.streamcloud.app.ui.StartupSplashScreen
import com.streamcloud.app.ui.theme.StreamCloudTheme
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.detectFormFactor
import java.io.File
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            StreamCloudTheme {
                var appReady by remember { mutableStateOf(false) }
                var splashVisible by remember { mutableStateOf(true) }

                LaunchedEffect(appReady) {
                    if (appReady) {
                        delay(650)
                        splashVisible = false
                    }
                }
                LaunchedEffect(Unit) {
                    delay(12_000)
                    splashVisible = false
                }

                Box(Modifier.fillMaxSize()) {
                    StreamCloudApp(
                        onStartupContentReady = { appReady = true },
                    )
                    AnimatedVisibility(
                        visible = splashVisible,
                        modifier = Modifier.fillMaxSize(),
                        enter = fadeIn(tween(durationMillis = 120)),
                        exit = fadeOut(tween(durationMillis = 320)),
                    ) {
                        StartupSplashScreen()
                    }
                }
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
                val isTv = detectFormFactor(this) == UiFormFactor.Tv
                val dialog = AlertDialog.Builder(this, R.style.Theme_StreamCloud_AppCompat)
                    .setTitle("Previous crash detected")
                    .setMessage(report.take(2000))
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .create()
                dialog.setOnShowListener {
                    if (isTv) {
                        dialog.findViewById<TextView>(android.R.id.message)
                            ?.setTextColor(Color.WHITE)
                        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                            ?.setTextColor(Color.WHITE)
                    }
                }
                dialog.show()
            }
        }
    }
}
