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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
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

    // YouTube CDN URLs contain ip=<IPv4-addr>.  If this OkHttpClient resolved the CDN
    // hostname to an IPv6 address, the outbound connection would exit from a different public
    // IP than the ip= value, and the CDN would return 403.  Forcing IPv4-only lookup ensures
    // the proxy's upstream requests use the same network path as the player-API call that
    // originally generated the stream URL.
    private val ipv4OnlyDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4  = all.filterIsInstance<Inet4Address>()
            return v4.ifEmpty { all }
        }
    }

    private val http = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
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
                runCatching { YtPlayerUtils.resolveAudioStream(track.videoId, sonosSafe = true) }.getOrNull()
            else null)
                ?: runCatching { NewPipeRepository.resolveAudioStream(track.watchUrl) }.getOrNull()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val method = requestLine.split(" ").firstOrNull() ?: "GET"

            // Drain ALL request headers before doing anything else.
            //
            // Java's Socket.close() sends TCP RST (not FIN) when unread bytes remain in the
            // input buffer.  Sonos performs a HEAD pre-validation of the stream URI inside its
            // SetAVTransportURI handler and if it receives a RST instead of a clean FIN it
            // treats the stream as unreachable and returns UPnP fault 716, which appears to
            // the user as "Sonos rejected the stream."  Reading past the blank header-terminator
            // line ensures the buffer is empty before we respond and close.
            val reqHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    reqHeaders[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            if (method != "GET" && method != "HEAD") {
                client.getOutputStream()
                    .write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }

            val track = currentTrack.get() ?: run {
                client.getOutputStream()
                    .write("HTTP/1.1 503 No Track Set\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }

            if (method == "HEAD") {
                client.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Type: audio/mp4\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                return
            }

            // Forward Range header from Sonos so partial-content/seeking works end-to-end.
            val rangeHeader = reqHeaders["range"]

            val streamUrl = resolveStreamUrl(track)
            if (streamUrl == null) {
                Log.w(TAG, "Could not resolve stream for ${track.videoId}")
                client.getOutputStream()
                    .write("HTTP/1.1 502 Stream Resolve Failed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }

            currentTrack.set(track.copy(resolvedUrl = streamUrl))

            val reqBuilder = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip")
                .header("Accept", "*/*")
            if (rangeHeader != null) {
                reqBuilder.header("Range", rangeHeader)
            }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "CDN error HTTP ${resp.code} for ${track.videoId}")
                    client.getOutputStream()
                        .write("HTTP/1.1 ${resp.code} Upstream Error\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }

                val contentType    = resp.header("Content-Type") ?: "audio/mp4"
                val contentLength  = resp.header("Content-Length")
                val contentRange   = resp.header("Content-Range")
                val statusLine     = if (resp.code == 206) "206 Partial Content" else "200 OK"

                val out = client.getOutputStream()
                val sb  = StringBuilder()
                sb.append("HTTP/1.1 $statusLine\r\n")
                sb.append("Content-Type: $contentType\r\n")
                if (contentLength != null) sb.append("Content-Length: $contentLength\r\n")
                if (contentRange  != null) sb.append("Content-Range: $contentRange\r\n")
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
