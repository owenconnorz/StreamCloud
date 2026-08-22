package com.streamcloud.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.streamcloud.app.data.recognition.MusicRecognitionState
import com.streamcloud.app.data.recognition.MusicRecognizer
import com.streamcloud.app.data.recognition.NoMatchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun MusicRecognitionHost(
    onSearchWithQuery: (String) -> Unit,
    content: @Composable (onRecognitionClick: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { MusicRecognizer() }
    var showRecognition by remember { mutableStateOf(false) }
    var recognitionState by remember { mutableStateOf<MusicRecognitionState>(MusicRecognitionState.Ready) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    var recognitionRequestId by remember { mutableIntStateOf(0) }

    DisposableEffect(recognizer) {
        onDispose {
            recognitionJob?.cancel()
            recognizer.close()
        }
    }

    fun startRecognition() {
        recognitionJob?.cancel()
        val requestId = recognitionRequestId + 1
        recognitionRequestId = requestId
        recognitionState = MusicRecognitionState.Listening
        recognitionJob = scope.launch {
            try {
                val result = recognizer.recognize()
                if (requestId == recognitionRequestId && showRecognition) {
                    recognitionState = MusicRecognitionState.Success(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoMatchException) {
                if (requestId == recognitionRequestId && showRecognition) {
                    recognitionState = MusicRecognitionState.NoMatch()
                }
            } catch (error: Exception) {
                if (requestId == recognitionRequestId && showRecognition) {
                    recognitionState = MusicRecognitionState.Error(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Please check your connection and try again.",
                    )
                }
            }
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecognition()
        } else {
            recognitionState = MusicRecognitionState.PermissionDenied
        }
    }

    fun openRecognition() {
        showRecognition = true
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startRecognition()
        } else {
            recognitionState = MusicRecognitionState.Ready
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    content(::openRecognition)

    if (showRecognition) {
        MusicRecognitionSheet(
            state = recognitionState,
            onStart = ::openRecognition,
            onRetry = ::openRecognition,
            onSearch = { result ->
                recognitionJob?.cancel()
                recognitionRequestId += 1
                showRecognition = false
                recognitionState = MusicRecognitionState.Ready
                onSearchWithQuery("${result.title} ${result.artist}")
            },
            onDismiss = {
                recognitionJob?.cancel()
                recognitionJob = null
                recognitionRequestId += 1
                showRecognition = false
                recognitionState = MusicRecognitionState.Ready
            },
        )
    }
}