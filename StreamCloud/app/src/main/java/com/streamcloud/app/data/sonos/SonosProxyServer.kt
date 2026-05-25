package com.streamcloud.app.data.sonos

import android.util.Log
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.YtPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SonosProxyServer {

    private const val TAG = "SonosProxy"

    data class TrackInfo(
        val videoId: String,
        val title: String,
        val watchUrl: String,

        val resolvedUrl: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentTrack = AtomicReference<TrackInfo?>(null)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile var port: Int = 0
        private set

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

    private fun resolveStreamUrl(track: TrackInfo): String? {

        track.resolvedUrl?.let { return it }
        return runBlocking {
            (if (track.videoId.isNotBlank())
                runCatching { YtPlayerUtils.resolveAudioStream(track.videoId) }.getOrNull()
            else null)
                ?: runCatching { NewPipeRepository.resolveAudioStream(track.watchUrl) }.getOrNull()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val method = requestLine.split(" ").firstOrNull() ?: "GET"


            if (method != "GET" && method != "HEAD") {
                client.getOutputStream().write("HTTP/1.0 405 Method Not Allowed\r\n\r\n".toByteArray())
                client.close()
                return
            }

            val track = currentTrack.get() ?: run {
                client.getOutputStream().write("HTTP/1.0 503 No Track Set\r\n\r\n".toByteArray())
                client.close()
                return
            }






            if (method == "HEAD") {
                client.getOutputStream().write(
                    "HTTP/1.0 200 OK\r\nContent-Type: audio/mp4\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                return
            }


            val streamUrl = resolveStreamUrl(track)
            if (streamUrl == null) {
                Log.w(TAG, "Could not resolve stream for ${track.videoId}")
                client.getOutputStream().write("HTTP/1.0 502 Stream Resolve Failed\r\n\r\n".toByteArray())
                client.close()
                return
            }


            currentTrack.set(track.copy(resolvedUrl = streamUrl))

            val req = Request.Builder()
                .url(streamUrl)
                .header(
                    "User-Agent",
                    "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip",
                )
                .header("Accept", "*/*")
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    client.getOutputStream()
                        .write("HTTP/1.0 ${resp.code} Upstream Error\r\n\r\n".toByteArray())
                    return
                }

                val contentType = resp.header("Content-Type") ?: "audio/mp4"
                val contentLength = resp.header("Content-Length")

                val out = client.getOutputStream()
                val sb = StringBuilder()
                sb.append("HTTP/1.0 200 OK\r\n")
                sb.append("Content-Type: $contentType\r\n")
                if (contentLength != null) sb.append("Content-Length: $contentLength\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")
                out.write(sb.toString().toByteArray())

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
