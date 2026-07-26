package com.streamcloud.app.cast.dlna

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object DlnaRepository {

    private const val TAG = "DlnaRepository"

    sealed interface State {
        object Idle : State
        object Discovering : State
        data class Ready(val devices: List<DlnaDevice>) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _selectedDevice = MutableStateFlow<DlnaDevice?>(null)
    val selectedDevice: StateFlow<DlnaDevice?> = _selectedDevice.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var pollJob: Job? = null

    fun discover(context: Context) {
        if (_state.value is State.Discovering) return
        _state.value = State.Discovering
        scope.launch {
            val devices = DlnaDiscovery.discover(context)
            Log.d(TAG, "Discovered ${devices.size} DLNA device(s)")
            _state.value = State.Ready(devices)
        }
    }

    fun selectDevice(device: DlnaDevice?) {
        _selectedDevice.value = device
        if (device == null) {
            pollJob?.cancel()
            pollJob = null
            _isPlaying.value = false
            _positionMs.value = 0L
            _durationMs.value = 0L
        }
    }

    fun startPolling() {
        val device = _selectedDevice.value ?: return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val info = runCatching { DlnaController.getPositionInfo(device) }.getOrNull()
                val state = runCatching { DlnaController.getTransportState(device) }.getOrNull()
                if (info != null) {
                    _positionMs.value = info.positionMs
                    _durationMs.value = info.durationMs
                }
                _isPlaying.value = state == "PLAYING"
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
