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

enum class PornPopMode(val label: String) {
    Image("Image"),
    Video("Video"),
    Undress("Undress"),
}

data class PornPopState(
    val mode: PornPopMode             = PornPopMode.Image,
    val prompt: String                = "",
    val style: String                 = "realistic",
    val selectedImageUri: Uri?        = null,
    val generating: Boolean           = false,
    val progressLabel: String         = "",
    val resultUrl: String?            = null,
    val isVideo: Boolean              = false,
    val taskId: String?               = null,
    val error: String?                = null,
    val myTasks: List<PornPopTaskResponse> = emptyList(),
    val loadingTasks: Boolean         = false,
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
        viewModelScope.launch {
            _state.update { it.copy(generating = true, error = null, resultUrl = null,
                progressLabel = "Sending request…") }
            try {
                val resp = when (s.mode) {
                    PornPopMode.Image -> PornPopApiClient.generateImage(
                        s.prompt, s.style, s.selectedImageUri, context, cookie)
                    PornPopMode.Video -> {
                        val uri = s.selectedImageUri
                            ?: throw Exception("Please upload an image first")
                        _state.update { it.copy(progressLabel = "Uploading image…") }
                        PornPopApiClient.generateVideo(uri, s.style, s.prompt, null, context, cookie)
                    }
                    PornPopMode.Undress -> {
                        val uri = s.selectedImageUri
                            ?: throw Exception("Please upload a photo first")
                        _state.update { it.copy(progressLabel = "Processing…") }
                        PornPopApiClient.undress(uri, context, cookie)
                    }
                }

                handleResponse(resp, isVideo = s.mode == PornPopMode.Video, cookie)

            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Something went wrong",
                    generating = false, progressLabel = "") }
            }
        }
    }

    private fun handleResponse(resp: PornPopTaskResponse, isVideo: Boolean, cookie: String?) {
        val url = resp.resultMediaUrl()
        val taskId = resp.taskId ?: resp.task_id ?: resp.id

        when {
            // Immediate result
            url != null && resp.status in COMPLETED_STATUSES || url != null && resp.status == null -> {
                _state.update { it.copy(resultUrl = url, isVideo = isVideo,
                    generating = false, progressLabel = "") }
            }
            // Async task — start polling
            taskId != null -> {
                _state.update { it.copy(taskId = taskId,
                    progressLabel = "Generating… this may take 1–3 min") }
                startPolling(taskId, isVideo, cookie)
            }
            // Error
            else -> {
                _state.update { it.copy(
                    error = resp.error ?: resp.message
                        ?: "API endpoint not yet available. Check back soon.",
                    generating = false, progressLabel = "") }
            }
        }
    }

    private fun startPolling(taskId: String, isVideo: Boolean, cookie: String?) {
        pollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < 40) {
                delay(4_000)
                attempts++
                _state.update { it.copy(
                    progressLabel = "Generating… (${ attempts * 4}s elapsed)") }
                try {
                    val resp = PornPopApiClient.checkTask(taskId, cookie)
                    val status = resp.status?.lowercase() ?: ""
                    when {
                        status in COMPLETED_STATUSES -> {
                            val url = resp.resultMediaUrl()
                            _state.update { it.copy(resultUrl = url, isVideo = isVideo,
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
            _state.update { it.copy(error = "Generation timed out — check My Work later",
                generating = false, progressLabel = "") }
        }
    }

    fun loadMyTasks(cookie: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(loadingTasks = true) }
            try {
                val tasks = PornPopApiClient.myTasks(cookie)
                _state.update { it.copy(myTasks = tasks, loadingTasks = false) }
            } catch (_: Exception) {
                _state.update { it.copy(loadingTasks = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return PornPopViewModel(context.applicationContext) as T
            }
        }
    }
}
