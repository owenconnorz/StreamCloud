package com.streamcloud.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.api.PornPopApiClient
import com.streamcloud.app.data.api.PornPopApiClient.COMPLETED_STATUSES
import com.streamcloud.app.data.api.PornPopApiClient.FAILED_STATUSES
import com.streamcloud.app.data.api.PornPopApiClient.resultMediaUrl
import com.streamcloud.app.data.api.PornPopTaskResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.random.Random

enum class PornPopMode(val label: String) {
    Image("Image"),
    Video("Video"),
    Undress("Undress"),
}

data class PornPopState(
    val mode: PornPopMode                  = PornPopMode.Image,
    val prompt: String                     = "",
    val style: String                      = "realistic",
    val selectedImageUri: Uri?             = null,
    val generating: Boolean                = false,
    val progressLabel: String              = "",
    val resultUrl: String?                 = null,
    val isVideo: Boolean                   = false,
    val taskId: String?                    = null,
    val error: String?                     = null,
    val myTasks: List<PornPopTaskResponse> = emptyList(),
    val loadingTasks: Boolean              = false,
)

class PornPopViewModel(private val context: Context) : ViewModel() {

    private val _state = MutableStateFlow(PornPopState())
    val state: StateFlow<PornPopState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun setMode(mode: PornPopMode) =
        _state.update { it.copy(mode = mode, error = null, resultUrl = null, taskId = null) }
    fun setPrompt(p: String) = _state.update { it.copy(prompt = p) }
    fun setStyle(s: String)  = _state.update { it.copy(style = s) }
    fun setImage(uri: Uri?)  = _state.update { it.copy(selectedImageUri = uri, error = null) }

    fun generate(cookie: String? = null) {
        val s = _state.value
        pollJob?.cancel()
        when (s.mode) {
            PornPopMode.Image   -> generateImage(s)
            PornPopMode.Video   -> generateVideo(s, cookie)
            PornPopMode.Undress -> generateUndress(s, cookie)
        }
    }

    // ── Image — served free by Pollinations.ai, no backend required ────────
    private fun generateImage(s: PornPopState) {
        viewModelScope.launch {
            _state.update { it.copy(generating = true, error = null, resultUrl = null,
                progressLabel = "Generating image…") }

            val styleHint = when (s.style) {
                "anime"       -> "anime style, high quality, detailed"
                "hentai"      -> "anime hentai style, explicit, uncensored"
                "3d"          -> "3D render, photorealistic, CGI"
                "illustrated" -> "illustrated, digital art, detailed"
                else          -> "photorealistic, high quality, detailed"  // realistic
            }
            val safePrompt = s.prompt.ifBlank { "beautiful woman, alluring, adult content" }
            val fullPrompt = "$safePrompt, $styleHint, nsfw, 8k"

            val encoded = URLEncoder.encode(fullPrompt, "UTF-8")
            val seed    = Random.nextInt(1, 999_999)
            // Pollinations.ai — free, no API key, returns JPEG directly
            val url = "https://image.pollinations.ai/prompt/$encoded" +
                      "?model=flux&width=896&height=1152&nologo=true&private=true&seed=$seed"

            _state.update { it.copy(
                resultUrl     = url,
                isVideo       = false,
                generating    = false,
                progressLabel = "",
            ) }
        }
    }

    // ── Video — requires pornpop.ai backend (GPU compute) ─────────────────
    private fun generateVideo(s: PornPopState, cookie: String?) {
        viewModelScope.launch {
            val uri = s.selectedImageUri ?: run {
                _state.update { it.copy(error = "Upload a photo first to generate a video") }
                return@launch
            }
            _state.update { it.copy(generating = true, error = null, resultUrl = null,
                progressLabel = "Uploading image…") }
            try {
                val resp = PornPopApiClient.generateVideo(
                    uri, s.style, s.prompt, null, context, cookie)
                handleAsyncResponse(resp, isVideo = true, cookie)
            } catch (e: Exception) {
                _state.update { it.copy(
                    error = "Video generation requires the pornpop.ai backend to be live. " +
                            "(${e.message})",
                    generating = false, progressLabel = "") }
            }
        }
    }

    // ── Undress — requires pornpop.ai backend (GPU compute) ───────────────
    private fun generateUndress(s: PornPopState, cookie: String?) {
        viewModelScope.launch {
            val uri = s.selectedImageUri ?: run {
                _state.update { it.copy(error = "Upload a photo first to undress it") }
                return@launch
            }
            _state.update { it.copy(generating = true, error = null, resultUrl = null,
                progressLabel = "Processing photo…") }
            try {
                val resp = PornPopApiClient.undress(uri, context, cookie)
                handleAsyncResponse(resp, isVideo = false, cookie)
            } catch (e: Exception) {
                _state.update { it.copy(
                    error = "AI Undress requires the pornpop.ai backend to be live. " +
                            "(${e.message})",
                    generating = false, progressLabel = "") }
            }
        }
    }

    private fun handleAsyncResponse(resp: PornPopTaskResponse, isVideo: Boolean, cookie: String?) {
        val url    = resp.resultMediaUrl()
        val taskId = resp.taskId ?: resp.task_id ?: resp.id
        when {
            url != null && (resp.status in COMPLETED_STATUSES || resp.status == null) ->
                _state.update { it.copy(resultUrl = url, isVideo = isVideo,
                    generating = false, progressLabel = "") }
            taskId != null -> {
                _state.update { it.copy(taskId = taskId,
                    progressLabel = "Generating… this may take 1–3 min") }
                startPolling(taskId, isVideo, cookie)
            }
            else -> _state.update { it.copy(
                error = resp.error ?: resp.message
                    ?: "Backend not yet available — image generation works now, video/undress coming soon.",
                generating = false, progressLabel = "") }
        }
    }

    private fun startPolling(taskId: String, isVideo: Boolean, cookie: String?) {
        pollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < 40) {
                delay(4_000)
                attempts++
                _state.update { it.copy(progressLabel = "Generating… (${attempts * 4}s elapsed)") }
                try {
                    val resp   = PornPopApiClient.checkTask(taskId, cookie)
                    val status = resp.status?.lowercase() ?: ""
                    when {
                        status in COMPLETED_STATUSES -> {
                            _state.update { it.copy(
                                resultUrl = resp.resultMediaUrl(), isVideo = isVideo,
                                generating = false, progressLabel = "") }
                            return@launch
                        }
                        status in FAILED_STATUSES -> {
                            _state.update { it.copy(
                                error = resp.error ?: "Generation failed",
                                generating = false, progressLabel = "") }
                            return@launch
                        }
                    }
                } catch (_: Exception) { /* keep polling */ }
            }
            _state.update { it.copy(error = "Timed out — check My Work later",
                generating = false, progressLabel = "") }
        }
    }

    fun loadMyTasks(cookie: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(loadingTasks = true) }
            try {
                _state.update { it.copy(
                    myTasks = PornPopApiClient.myTasks(cookie),
                    loadingTasks = false) }
            } catch (_: Exception) {
                _state.update { it.copy(loadingTasks = false) }
            }
        }
    }

    override fun onCleared() { super.onCleared(); pollJob?.cancel() }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return PornPopViewModel(context.applicationContext) as T
            }
        }
    }
}
