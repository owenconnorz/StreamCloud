package com.streamcloud.app.data.discord

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.audio.MusicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object DiscordRpcService {

    private const val TAG = "DiscordRpc"
    private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

    enum class RpcStatus { IDLE, CONNECTING, CONNECTED, ERROR }

    data class RpcConfig(
        val appName: String = "StreamCloud",
        val activityType: Int = 2,
        val showTitle: Boolean = true,
        val showArtist: Boolean = true,
        val showArtwork: Boolean = true,
        val showTimestamps: Boolean = true,
        val timestampMode: String = "elapsed",
        val clearOnPause: Boolean = false,
        val showButton: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(RpcStatus.IDLE)
    val status: StateFlow<RpcStatus> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var heartbeatIntervalMs: Long = 41250L
    @Volatile private var lastSeq: Int? = null
    @Volatile private var gatewayConnected = false
    @Volatile private var userStopped = false
    @Volatile private var token: String = ""
    @Volatile private var config = RpcConfig()

    @Volatile private var trackTitle: String = ""
    @Volatile private var trackArtist: String = ""
    @Volatile private var trackArtwork: String? = null
    @Volatile private var trackVideoId: String? = null
    @Volatile private var isPlaying: Boolean = false
    // Stored in MILLISECONDS — Discord gateway timestamps.start/end must be Unix ms.
    @Volatile private var trackStartMs: Long = 0L
    @Volatile private var trackEndMs: Long = 0L

    private var musicListener: Player.Listener? = null
    private var attachedController: androidx.media3.session.MediaController? = null

    fun start(context: Context, token: String, cfg: RpcConfig) {
        stop()
        userStopped = false
        this.token = token.trim()
        this.config = cfg
        if (this.token.isBlank()) { _status.value = RpcStatus.IDLE; return }
        _status.value = RpcStatus.CONNECTING
        _errorMessage.value = ""
        scope.launch { attachMusicListener(context.applicationContext) }
        scope.launch { connectGateway() }
    }

    fun updateConfig(cfg: RpcConfig) {
        config = cfg
        if (gatewayConnected) scope.launch { sendPresence() }
    }

    fun stop() {
        userStopped = true
        reconnectJob?.cancel(); reconnectJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        ws?.close(1000, "User stopped"); ws = null
        gatewayConnected = false
        scope.launch(Dispatchers.Main) {
            musicListener?.let { attachedController?.removeListener(it) }
            musicListener = null
            attachedController = null
        }
        _status.value = RpcStatus.IDLE
        _errorMessage.value = ""
    }

    private suspend fun attachMusicListener(context: Context) {
        val ctrl = runCatching {
            withContext(Dispatchers.Main) { MusicController.get(context) }
        }.getOrNull() ?: return

        withContext(Dispatchers.Main) {
            attachedController = ctrl
            val md = ctrl.mediaMetadata
            trackTitle = md.title?.toString() ?: ""
            trackArtist = md.artist?.toString() ?: ""
            trackArtwork = md.artworkUri?.toString()
            trackVideoId = extractVideoId(ctrl.currentMediaItem?.mediaId)
            isPlaying = ctrl.isPlaying
            refreshTimestamps(ctrl)

            val listener = object : Player.Listener {
                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    trackTitle = metadata.title?.toString() ?: ""
                    trackArtist = metadata.artist?.toString() ?: ""
                    trackArtwork = metadata.artworkUri?.toString()
                    trackVideoId = extractVideoId(ctrl.currentMediaItem?.mediaId)
                    scope.launch {
                        withContext(Dispatchers.Main) { refreshTimestamps(ctrl) }
                        sendPresence()
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    scope.launch {
                        if (playing) withContext(Dispatchers.Main) { refreshTimestamps(ctrl) }
                        sendPresence()
                    }
                }
            }
            musicListener = listener
            ctrl.addListener(listener)
        }
    }

    private fun refreshTimestamps(ctrl: androidx.media3.session.MediaController) {
        val posMs = ctrl.currentPosition.coerceAtLeast(0L)
        val durMs = ctrl.duration.takeIf { it > 0L } ?: 0L
        val nowMs = System.currentTimeMillis()
        // Store raw Unix ms — Discord gateway timestamps.start/end are Unix milliseconds.
        trackStartMs = nowMs - posMs
        trackEndMs = if (durMs > 0L) nowMs + (durMs - posMs) else 0L
    }

    /**
     * Extracts a bare YouTube video ID from a mediaId that may be a full URL
     * (https://www.youtube.com/watch?v=XXXXX) or already just the 11-char ID.
     */
    private fun extractVideoId(mediaId: String?): String? {
        if (mediaId.isNullOrBlank()) return null
        val vParam = mediaId.substringAfter("v=", "").substringBefore("&").trim()
        if (vParam.isNotBlank()) return vParam
        // Some implementations store the raw video ID directly
        return mediaId.takeIf { it.length in 10..12 && !it.contains('/') && !it.contains('?') }
    }

    /**
     * Returns a publicly accessible artwork URL Discord can display.
     * Prefers a YouTube hqdefault thumbnail built from the video ID; falls back
     * to the raw artworkUri only if it is already an https:// URL.
     */
    private fun buildArtworkUrl(videoId: String?, fallback: String?): String? {
        if (!videoId.isNullOrBlank()) return "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        return fallback?.takeIf { it.startsWith("https://") }
    }

    private fun connectGateway() {
        val req = Request.Builder().url(GATEWAY_URL).build()
        ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gateway open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleGatewayMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway closed $code: $reason")
                gatewayConnected = false
                if (!userStopped && token.isNotBlank()) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Gateway failure: ${t.message}")
                gatewayConnected = false
                _status.value = RpcStatus.ERROR
                _errorMessage.value = t.message?.take(80) ?: "Connection failed"
                if (!userStopped && token.isNotBlank()) scheduleReconnect()
            }
        })
    }

    private fun handleGatewayMessage(text: String) {
        try {
            val json = JSONObject(text)
            val op = json.getInt("op")
            val seq = json.optInt("s", -1).takeIf { it >= 0 }
            seq?.let { lastSeq = it }
            val d = json.optJSONObject("d")

            when (op) {
                10 -> {
                    heartbeatIntervalMs = d?.getLong("heartbeat_interval") ?: 41250L
                    sendHeartbeat()
                    startHeartbeatLoop()
                    sendIdentify()
                }
                11 -> Log.v(TAG, "Heartbeat ACK")
                0 -> {
                    if (json.optString("t") == "READY") {
                        gatewayConnected = true
                        _status.value = RpcStatus.CONNECTED
                        _errorMessage.value = ""
                        Log.d(TAG, "Discord RPC ready")
                        scope.launch { sendPresence() }
                    }
                }
                7 -> {
                    Log.d(TAG, "Reconnect requested")
                    ws?.close(4000, "Reconnect")
                    scheduleReconnect(0L)
                }
                9 -> {
                    Log.w(TAG, "Invalid session")
                    gatewayConnected = false
                    _status.value = RpcStatus.ERROR
                    _errorMessage.value = "Invalid session — check your Discord token"
                    ws?.close(1000, "Invalid session")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }

    private fun sendHeartbeat() {
        val seq = lastSeq
        val payload = JSONObject().apply {
            put("op", 1)
            if (seq != null) put("d", seq) else put("d", JSONObject.NULL)
        }
        ws?.send(payload.toString())
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                sendHeartbeat()
            }
        }
    }

    private fun sendIdentify() {
        val payload = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", token)
                put("intents", 0)
                put("properties", JSONObject().apply {
                    put("os", "android")
                    put("browser", "Discord Android")
                    put("device", "StreamCloud")
                })
            })
        }
        ws?.send(payload.toString())
    }

    private suspend fun sendPresence() {
        if (!gatewayConnected) return
        val cfg = config
        val playing = isPlaying
        val title = trackTitle
        val artist = trackArtist
        val startMs = trackStartMs
        val endMs = trackEndMs
        val artworkUrl = buildArtworkUrl(trackVideoId, trackArtwork)

        val activities = JSONArray()
        val showActivity = !cfg.clearOnPause || playing

        if (showActivity && title.isNotBlank()) {
            val activity = JSONObject()
            activity.put("name", cfg.appName.ifBlank { "StreamCloud" })
            activity.put("type", cfg.activityType)

            if (cfg.showTitle && title.isNotBlank()) {
                activity.put("details", title.take(128))
            }
            if (cfg.showArtist && artist.isNotBlank()) {
                activity.put("state", "by ${artist.take(125)}")
            }

            // Discord gateway timestamps.start/end are Unix MILLISECONDS.
            if (cfg.showTimestamps && startMs > 0L) {
                val timestamps = JSONObject()
                when (cfg.timestampMode) {
                    "remaining" -> if (endMs > 0L) timestamps.put("end", endMs)
                    "bar" -> {
                        timestamps.put("start", startMs)
                        if (endMs > 0L) timestamps.put("end", endMs)
                    }
                    else -> timestamps.put("start", startMs)
                }
                if (timestamps.length() > 0) activity.put("timestamps", timestamps)
            }

            if (cfg.showArtwork && !artworkUrl.isNullOrBlank()) {
                val assets = JSONObject()
                assets.put("large_image", artworkUrl)
                assets.put("large_text", title.take(128))
                activity.put("assets", assets)
            }

            if (cfg.showButton && title.isNotBlank()) {
                val query = title.take(50).replace(" ", "+")
                val buttons = JSONArray()
                buttons.put(JSONObject().apply {
                    put("label", "Listen on YouTube Music")
                    put("url", "https://music.youtube.com/search?q=$query")
                })
                activity.put("buttons", buttons)
            }

            activities.put(activity)
        }

        val payload = JSONObject().apply {
            put("op", 3)
            put("d", JSONObject().apply {
                put("since", JSONObject.NULL)
                put("activities", activities)
                put("status", "online")
                put("afk", false)
            })
        }
        ws?.send(payload.toString())
    }

    private fun scheduleReconnect(delayMs: Long = 5000L) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (!userStopped && token.isNotBlank()) {
                _status.value = RpcStatus.CONNECTING
                connectGateway()
            }
        }
    }
}
