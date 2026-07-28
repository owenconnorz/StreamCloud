package com.streamcloud.app.data.listentogether

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamcloud.app.audio.MusicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// ── Data models ───────────────────────────────────────────────────────────────

data class LtMember(
    val id: String,
    val name: String,
    val isHost: Boolean,
)

sealed class LtConnectionState {
    object Idle : LtConnectionState()
    object Connecting : LtConnectionState()
    data class Connected(val roomCode: String, val isHost: Boolean) : LtConnectionState()
    data class Error(val message: String) : LtConnectionState()
}

sealed class LtCommand {
    data class Play(val positionMs: Long, val trackId: String, val trackTitle: String) : LtCommand()
    data class Pause(val positionMs: Long) : LtCommand()
    data class Seek(val positionMs: Long) : LtCommand()
    data class TrackChange(val trackId: String, val trackTitle: String) : LtCommand()
    data class SyncState(val isPlaying: Boolean, val positionMs: Long, val trackId: String, val trackTitle: String) : LtCommand()
    data class MemberList(val members: List<LtMember>, val youId: String, val youIsHost: Boolean) : LtCommand()
    data class MemberJoin(val id: String, val name: String, val isHost: Boolean) : LtCommand()
    data class MemberLeave(val id: String, val name: String) : LtCommand()
    data class PromotedToHost(val newHostId: String) : LtCommand()
    data class ServerError(val message: String) : LtCommand()
}

// ── Repository ────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
object ListenTogetherRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var playerListener: Player.Listener? = null
    private var myId: String = ""

    private val _state = MutableStateFlow<LtConnectionState>(LtConnectionState.Idle)
    val state: StateFlow<LtConnectionState> = _state.asStateFlow()

    private val _members = MutableStateFlow<List<LtMember>>(emptyList())
    val members: StateFlow<List<LtMember>> = _members.asStateFlow()

    private val _commands = MutableSharedFlow<LtCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<LtCommand> = _commands.asSharedFlow()

    // ── Create room on server ─────────────────────────────────────────────────

    suspend fun createRoom(backendUrl: String): Result<String> = runCatching {
        val url = backendUrl.trimEnd('/') + "/listen-together/room"
        val req = Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("Server returned ${resp.code}")
        val body = resp.body?.string() ?: error("Empty response")
        json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
            ?: error("No code in response")
    }

    // ── Connect WebSocket to a room ───────────────────────────────────────────

    fun connect(backendUrl: String, roomCode: String, displayName: String, context: Context) {
        disconnect()
        _state.value = LtConnectionState.Connecting

        val encodedName = java.net.URLEncoder.encode(displayName.take(24), "UTF-8")
        val wsUrl = backendUrl.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/listen-together/ws/$roomCode?name=$encodedName"

        val req = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch { handleMessage(text, roomCode, context) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _state.value = LtConnectionState.Error(t.message ?: "Connection failed")
                cleanupListener(context)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (_state.value !is LtConnectionState.Idle) {
                    _state.value = LtConnectionState.Idle
                }
                _members.value = emptyList()
                cleanupListener(context)
            }
        })
    }

    // ── Handle incoming message ───────────────────────────────────────────────

    private suspend fun handleMessage(text: String, roomCode: String, context: Context) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "member_list" -> {
                val members = parseMemberList(obj)
                val youId = obj["you_id"]?.jsonPrimitive?.content ?: ""
                val youIsHost = obj["you_is_host"]?.jsonPrimitive?.content?.toBoolean() ?: false
                myId = youId
                _members.value = members
                _state.value = LtConnectionState.Connected(roomCode, youIsHost)
                _commands.emit(LtCommand.MemberList(members, youId, youIsHost))
                if (youIsHost) attachHostListener(context)
            }
            "member_join" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                val name = obj["name"]?.jsonPrimitive?.content ?: "Friend"
                val isHost = obj["is_host"]?.jsonPrimitive?.content?.toBoolean() ?: false
                _members.value = _members.value + LtMember(id, name, isHost)
                _commands.emit(LtCommand.MemberJoin(id, name, isHost))
            }
            "member_leave" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                val name = obj["name"]?.jsonPrimitive?.content ?: "Friend"
                _members.value = _members.value.filter { it.id != id }
                _commands.emit(LtCommand.MemberLeave(id, name))
            }
            "promoted_to_host" -> {
                val newHostId = obj["new_host_id"]?.jsonPrimitive?.content ?: return
                _members.value = _members.value.map {
                    it.copy(isHost = it.id == newHostId)
                }
                if (newHostId == myId) {
                    val current = _state.value
                    if (current is LtConnectionState.Connected) {
                        _state.value = current.copy(isHost = true)
                        attachHostListener(context)
                    }
                }
                _commands.emit(LtCommand.PromotedToHost(newHostId))
            }
            "play" -> {
                val posMs = obj["position_ms"]?.jsonPrimitive?.long ?: 0L
                val trackId = obj["track_id"]?.jsonPrimitive?.content ?: ""
                val trackTitle = obj["track_title"]?.jsonPrimitive?.content ?: ""
                _commands.emit(LtCommand.Play(posMs, trackId, trackTitle))
                val state = _state.value
                if (state is LtConnectionState.Connected && !state.isHost) {
                    applySeekAndPlay(posMs, context)
                }
            }
            "pause" -> {
                val posMs = obj["position_ms"]?.jsonPrimitive?.long ?: 0L
                _commands.emit(LtCommand.Pause(posMs))
                val state = _state.value
                if (state is LtConnectionState.Connected && !state.isHost) {
                    applyPause(context)
                }
            }
            "seek" -> {
                val posMs = obj["position_ms"]?.jsonPrimitive?.long ?: 0L
                _commands.emit(LtCommand.Seek(posMs))
                val state = _state.value
                if (state is LtConnectionState.Connected && !state.isHost) {
                    applySeek(posMs, context)
                }
            }
            "track_change" -> {
                val trackId = obj["track_id"]?.jsonPrimitive?.content ?: ""
                val trackTitle = obj["track_title"]?.jsonPrimitive?.content ?: ""
                _commands.emit(LtCommand.TrackChange(trackId, trackTitle))
            }
            "sync_state" -> {
                val isPlaying = obj["is_playing"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val posMs = obj["position_ms"]?.jsonPrimitive?.long ?: 0L
                val serverTimeMs = obj["server_time_ms"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                val trackId = obj["track_id"]?.jsonPrimitive?.content ?: ""
                val trackTitle = obj["track_title"]?.jsonPrimitive?.content ?: ""
                // Latency correction: add half round-trip estimate
                val latency = (System.currentTimeMillis() - serverTimeMs).coerceAtLeast(0L) / 2
                val correctedPos = posMs + latency
                _commands.emit(LtCommand.SyncState(isPlaying, correctedPos, trackId, trackTitle))
                val state = _state.value
                if (state is LtConnectionState.Connected && !state.isHost) {
                    if (isPlaying) applySeekAndPlay(correctedPos, context)
                    else { applySeek(correctedPos, context); applyPause(context) }
                }
            }
            "sync_request" -> {
                // Server is asking host to send current state
                val requesterId = obj["requester_id"]?.jsonPrimitive?.content
                scope.launch(Dispatchers.Main) {
                    val mc = runCatching { MusicController.get(context) }.getOrNull() ?: return@launch
                    val mediaId = mc.currentMediaItem?.mediaId ?: ""
                    val title = mc.mediaMetadata.title?.toString() ?: ""
                    val payload = buildJsonObject {
                        put("type", "sync_state")
                        put("is_playing", mc.isPlaying)
                        put("position_ms", mc.currentPosition)
                        put("track_id", mediaId)
                        put("track_title", title)
                        if (requesterId != null) put("requester_id", requesterId)
                    }
                    sendRaw(payload.toString())
                }
            }
            "error" -> {
                val msg = obj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                _state.value = LtConnectionState.Error(msg)
                _commands.emit(LtCommand.ServerError(msg))
            }
        }
    }

    // ── Host: attach Player listener to broadcast events ──────────────────────

    private fun attachHostListener(context: Context) {
        scope.launch(Dispatchers.Main) {
            val mc = runCatching { MusicController.get(context) }.getOrNull() ?: return@launch
            // Remove any old listener first
            playerListener?.let { mc.removeListener(it) }
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val s = _state.value
                    if (s !is LtConnectionState.Connected || !s.isHost) return
                    val pos = mc.currentPosition
                    val mediaId = mc.currentMediaItem?.mediaId ?: ""
                    val title = mc.mediaMetadata.title?.toString() ?: ""
                    val payload = if (isPlaying) {
                        buildJsonObject {
                            put("type", "play")
                            put("position_ms", pos)
                            put("track_id", mediaId)
                            put("track_title", title)
                        }
                    } else {
                        buildJsonObject {
                            put("type", "pause")
                            put("position_ms", pos)
                        }
                    }
                    sendRaw(payload.toString())
                }

                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int,
                ) {
                    val s = _state.value
                    if (s !is LtConnectionState.Connected || !s.isHost) return
                    val trackId = mediaItem?.mediaId ?: ""
                    val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                    val payload = buildJsonObject {
                        put("type", "track_change")
                        put("track_id", trackId)
                        put("track_title", title)
                        put("position_ms", 0L)
                    }
                    sendRaw(payload.toString())
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                    val s = _state.value
                    if (s !is LtConnectionState.Connected || !s.isHost) return
                    val payload = buildJsonObject {
                        put("type", "seek")
                        put("position_ms", newPosition.positionMs)
                    }
                    sendRaw(payload.toString())
                }
            }
            mc.addListener(listener)
            playerListener = listener
        }
    }

    private fun cleanupListener(context: Context) {
        val listener = playerListener ?: return
        playerListener = null
        scope.launch(Dispatchers.Main) {
            runCatching { MusicController.get(context) }.getOrNull()?.removeListener(listener)
        }
    }

    // ── Guest: apply incoming commands to local player ────────────────────────

    private fun applySeekAndPlay(positionMs: Long, context: Context) {
        scope.launch(Dispatchers.Main) {
            val mc = runCatching { MusicController.get(context) }.getOrNull() ?: return@launch
            mc.seekTo(positionMs)
            mc.play()
        }
    }

    private fun applyPause(context: Context) {
        scope.launch(Dispatchers.Main) {
            runCatching { MusicController.get(context) }.getOrNull()?.pause()
        }
    }

    private fun applySeek(positionMs: Long, context: Context) {
        scope.launch(Dispatchers.Main) {
            runCatching { MusicController.get(context) }.getOrNull()?.seekTo(positionMs)
        }
    }

    // ── Send / control ────────────────────────────────────────────────────────

    fun requestSync() {
        sendRaw(buildJsonObject { put("type", "sync_request") }.toString())
    }

    private fun sendRaw(json: String) {
        socket?.send(json)
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        socket?.close(1000, "User left")
        socket = null
        _state.value = LtConnectionState.Idle
        _members.value = emptyList()
        myId = ""
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMemberList(obj: JsonObject): List<LtMember> {
        val arr = obj["members"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            LtMember(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.content ?: "Friend",
                isHost = o["is_host"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        }
    }
}
