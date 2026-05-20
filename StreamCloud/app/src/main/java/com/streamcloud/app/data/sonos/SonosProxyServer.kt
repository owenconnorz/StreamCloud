package com.streamcloud.app.data.sonos

import android.util.Log
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal HTTP proxy server that runs on a random local port and streams
 * audio to Sonos on behalf of the app.
 *
 * Why a proxy?
 *  • YouTube audio stream URLs are short-lived (~6 h) and are keyed to the
 *    requesting IP address when fetched via the InnerTube ANDROID_MUSIC client.
 *    If Sonos (different IP) tries to use the URL the phone resolved, YouTube
 *    may reject it. The proxy solves this: Sonos streams from the phone, the
 *    phone streams from YouTube — one consistent IP throughout.
 *  • YouTube CDN requires a matching User-Agent; the proxy injects it.
 *
 * Usage:
 *  1. Call [start] to bind the ServerSocket and begin accepting connections.
 *  2. Call [setTrack] before (or any time during) playback to update the
 *     current videoId + title. The proxy resolves a fresh stream URL on every
 *     new TCP connection, so URL expiry is never an issue.
 *  3. The proxy URL exposed by [proxyUrl] is what you hand to Sonos via
 *     [SonosController.setUri].
 *  4. Call [stop] when casting ends to free the port.
 */
object SonosProxyServer {

    private const val TAG = "SonosProxy"

    data class TrackInfo(val videoId: String, val title: String, val watchUrl: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentTrack = AtomicReference<TrackInfo?>(null)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile var port: Int = 0
        private set

    val proxyUrl: String get() = "http://{{LOCAL_IP}}:$port/stream"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun setTrack(info: TrackInfo) {
        currentTrack.set(info)
    }

    fun start(localIp: String): String {
        stop()
        val ss = ServerSocket(0)
        serverSocket = ss
        port = ss.localPort
        val url = "http://$localIp:$port/stream"
        Log.d(TAG, "Proxy started on $url")
        acceptJob = scope.launch { acceptLoop(ss) }
        return url
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        currentTrack.set(null)
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return

            if (!requestLine.startsWith("GET")) {
                client.getOutputStream().write("HTTP/1.0 405 Method Not Allowed\r\n\r\n".toByteArray())
                client.close()
                return
            }

            val track = currentTrack.get() ?: run {
                client.getOutputStream().write("HTTP/1.0 503 No Track Set\r\n\r\n".toByteArray())
                client.close()
                return
            }

            // Resolve a fresh stream URL for this connection
            val streamUrl = YtPlayerUtils.resolveAudioStream(track.videoId)
                ?: runCatching { NewPipeRepository.resolveAudioStream(track.watchUrl) }.getOrNull()

            if (streamUrl == null) {
                Log.w(TAG, "Could not resolve stream for ${track.videoId}")
                client.getOutputStream().write("HTTP/1.0 502 Stream Resolve Failed\r\n\r\n".toByteArray())
                client.close()
                return
            }

            val req = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip")
                .header("Accept", "*/*")
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    client.getOutputStream().write("HTTP/1.0 ${resp.code} Upstream Error\r\n\r\n".toByteArray())
                    return
                }

                val contentType = resp.header("Content-Type", "audio/mp4") ?: "audio/mp4"
                val contentLength = resp.header("Content-Length")

                val out = client.getOutputStream()
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.0 200 OK\r\n")
                headerBuilder.append("Content-Type: $contentType\r\n")
                headerBuilder.append("Accept-Ranges: none\r\n")
                if (contentLength != null) headerBuilder.append("Content-Length: $contentLength\r\n")
                headerBuilder.append("Connection: close\r\n")
                headerBuilder.append("\r\n")
                out.write(headerBuilder.toString().toByteArray())

                resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handler error: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }
}
