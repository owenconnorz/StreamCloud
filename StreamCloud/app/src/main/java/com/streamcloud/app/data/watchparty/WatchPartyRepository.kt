package com.streamcloud.app.data.watchparty

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Independent StreamCloud protocol. Never sends a media URL or provider credentials. */
object WatchPartyRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(PartyState())
    val state = mutableState.asStateFlow()
    @Volatile private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var retry: Job? = null
    private var token = ""
    @Volatile private var generation = 0
    private var retryCount = 0
    private var server = ""
    private var preferences: android.content.SharedPreferences? = null
    @Volatile private var clockOffset = 0L

    fun initialize(context: Context) {
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences("watch_party_v1", Context.MODE_PRIVATE)
            server = preferences?.getString("server", "").orEmpty()
        }
    }

    val serverUrl: String get() = server
    val displayName: String get() = preferences?.getString("name", "Friend").orEmpty().ifBlank { "Friend" }
    fun saveConfiguration(url: String, name: String): Result<Unit> = runCatching {
        val parsed = url.trim().trimEnd('/').toHttpUrlOrNull() ?: error("Enter a valid HTTPS server address.")
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null) { "Use an HTTPS address without passwords or query parameters." }
        require(name.trim().length in 1..40) { "Enter a display name of 1–40 characters." }
        require(mutableState.value.room == null || parsed.toString().trimEnd('/') == server) {
            "Leave your current party before changing servers."
        }
        server = parsed.toString().trimEnd('/')
        preferences?.edit()?.putString("server", server)?.putString("name", name.trim())?.apply()
    }

    fun create(media: PartyMedia? = null) = start(null, media)
    fun join(code: String) = start(code.uppercase().filter { it.isLetterOrDigit() }, null)

    private fun start(code: String?, media: PartyMedia?) {
        if (mutableState.value.busy || mutableState.value.room != null) return
        val requestGeneration = ++generation
        mutableState.value = PartyState(busy = true)
        scope.launch {
            runCatching {
                require(server.isNotBlank()) { "Set your Watch Party server address first." }
                if (code != null) require(code.length == 8 && code.all { it in 'A'..'Z' || it in '0'..'9' }) {
                    "Enter the eight-character StreamCloud room code."
                }
                val payload = buildJsonObject {
                    put("name", displayName)
                    media?.let { put("media", json.encodeToJsonElement(PartyMedia.serializer(), it)) }
                }
                val path = if (code == null) "/watch-party/rooms" else "/watch-party/rooms/$code/join"
                val request = Request.Builder().url(server + path)
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    if (!response.isSuccessful) {
                        error(obj?.get("detail")?.let { (it as? JsonPrimitive)?.contentOrNull }
                            ?: "Party server returned HTTP ${response.code}.")
                    }
                    requireNotNull(obj) { "The server returned an invalid room." }
                    Triple(
                        obj.getValue("token").jsonPrimitive.content,
                        obj.getValue("member_id").jsonPrimitive.content,
                        json.decodeFromJsonElement(PartyRoom.serializer(), obj.getValue("room")),
                    )
                }
            }.onSuccess { (newToken, id, room) ->
                if (generation != requestGeneration) return@onSuccess
                token = newToken
                clockOffset = room.serverTime - System.currentTimeMillis()
                mutableState.value = PartyState(room, id, busy = false)
                retryCount = 0
                connect(requestGeneration)
            }.onFailure {
                if (generation == requestGeneration) mutableState.value = PartyState(error = it.message ?: "Could not reach the party server.")
            }
        }
    }

    private fun connect(expectedGeneration: Int) {
        val code = mutableState.value.room?.code ?: return
        val request = Request.Builder().url(server + "/watch-party/ws").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (generation != expectedGeneration) { ws.close(1000, null); return }
                ws.send(buildJsonObject { put("code", code); put("token", token) }.toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (generation != expectedGeneration || text.length > 65536) return
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "state" -> {
                            val room = json.decodeFromJsonElement(PartyRoom.serializer(), obj)
                            val old = mutableState.value
                            if (room.seq < (old.room?.seq ?: 0)) return@runCatching
                            mutableState.value = old.copy(room = room, connected = true, error = null)
                            retryCount = 0
                            if (heartbeat?.isActive != true) heartbeat = scope.launch {
                                while (isActive && generation == expectedGeneration) {
                                    command(buildJsonObject { put("type", "ping"); put("sent_at", System.currentTimeMillis()) })
                                    delay(10_000)
                                }
                            }
                        }
                        "pong" -> {
                            val sent = obj["sent_at"]?.jsonPrimitive?.longOrNull ?: return@runCatching
                            val current = System.currentTimeMillis()
                            if (current - sent in 0..2000) {
                                clockOffset = obj.getValue("server_time").jsonPrimitive.long + (current - sent) / 2 - current
                            }
                        }
                        "removed", "ended" -> {
                            val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "The party ended."
                            leave()
                            mutableState.value = PartyState(error = message)
                        }
                        "error" -> mutableState.value = mutableState.value.copy(
                            error = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Party request failed.",
                        )
                    }
                }.onFailure {
                    mutableState.value = mutableState.value.copy(error = "Invalid response from the party server.")
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { reconnect(ws, expectedGeneration) }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { reconnect(ws, expectedGeneration) }
        })
    }

    private fun reconnect(failedSocket: WebSocket, expectedGeneration: Int) {
        if (generation != expectedGeneration || socket !== failedSocket || mutableState.value.room == null) return
        heartbeat?.cancel(); heartbeat = null
        mutableState.value = mutableState.value.copy(connected = false, error = "Reconnecting to your party…")
        retry?.cancel()
        retry = scope.launch {
            delay((2_000L shl retryCount.coerceAtMost(3)).coerceAtMost(20_000))
            retryCount++
            if (generation == expectedGeneration) connect(expectedGeneration)
        }
    }

    fun leave() {
        generation++
        retry?.cancel(); heartbeat?.cancel(); heartbeat = null
        socket?.send("""{"type":"leave"}""")
        socket?.close(1000, "Left party")
        socket = null; token = ""
        mutableState.value = PartyState()
    }

    private fun command(obj: JsonObject) { socket?.send(obj.toString()) }
    fun end() { if (mutableState.value.isHost) command(buildJsonObject { put("type", "end") }) }
    fun kick(id: String) { if (mutableState.value.isHost) command(buildJsonObject { put("type", "kick"); put("member_id", id) }) }
    fun hostOnly(value: Boolean) {
        if (mutableState.value.isHost) command(buildJsonObject { put("type", "host_only"); put("enabled", value) })
    }
    fun selectMedia(media: PartyMedia) {
        if (mutableState.value.isHost) command(buildJsonObject {
            put("type", "media"); put("media", json.encodeToJsonElement(PartyMedia.serializer(), media))
        })
    }
    fun status(value: String) = command(buildJsonObject { put("type", "status"); put("status", value) })
    fun playback(media: PartyMedia, position: Long, paused: Boolean, rate: Float) {
        if (!mutableState.value.canControl) return
        command(buildJsonObject {
            put("type", "playback"); put("media", json.encodeToJsonElement(PartyMedia.serializer(), media))
            put("position_ms", position.coerceAtLeast(0)); put("paused", paused); put("rate", rate)
        })
    }
    fun position(playback: PartyPlayback) = projectedPartyPosition(
        playback.positionMs, playback.paused, playback.rate, playback.at,
        System.currentTimeMillis() + clockOffset,
    )
}
