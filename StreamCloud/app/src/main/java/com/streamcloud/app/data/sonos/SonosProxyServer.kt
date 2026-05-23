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
        /** MIME type of resolvedUrl — Sonos validates this during SetAVTransportURI.
         *  Must be a type Sonos supports (audio/mp4, audio/mpeg, audio/flac, etc.).
         *  WebM/Opus is NOT supported by any Sonos hardware. */
        val mimeType: String = "audio/mp4",
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
        .followRedirects(true)
        .build()

    fun setTrack(info: TrackInfo) {
        currentTrack.set(info)
    }

    fun start(localIp: String): String {
        stop()
        val ss = ServerSocket(0)
        serverSocket = ss
        port = ss.localPort
        val url = "http://$localIp:$port/stream.m4a"
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
            (if (track.videoId.isNotBlank()) YtPlayerUtils.resolveAudioStream(track.videoId) else null)
                ?: runCatching { NewPipeRepository.resolveAudioStream(track.watchUrl) }.getOrNull()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))

            val requestLine = reader.readLine() ?: return
            val method = requestLine.split(" ").firstOrNull() ?: "GET"

            // Read all request headers (Sonos sends Range: bytes=0-)
            val requestHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    requestHeaders[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val rangeHeader = requestHeaders["range"]
            Log.d(TAG, "$method /stream  Range=$rangeHeader")

            if (method != "GET" && method != "HEAD") {
                respond(client, "HTTP/1.1 405 Method Not Allowed", emptyMap(), null)
                return
            }

            val track = currentTrack.get() ?: run {
                respond(client, "HTTP/1.1 503 No Track Set", emptyMap(), null)
                return
            }

            // HEAD: reply with the actual MIME type of the resolved stream.
            // Sonos validates Content-Type during SetAVTransportURI — returning
            // the correct type avoids UPnP error 714 (Illegal MIME-type).
            if (method == "HEAD") {
                val headers = mapOf(
                    "Content-Type"  to track.mimeType,
                    "Accept-Ranges" to "bytes",
                    "Connection"    to "close",
                )
                respond(client, "HTTP/1.1 200 OK", headers, null)
                return
            }

            val streamUrl = resolveStreamUrl(track)
            if (streamUrl == null) {
                Log.w(TAG, "Could not resolve stream for ${track.videoId}")
                respond(client, "HTTP/1.1 502 Stream Resolve Failed", emptyMap(), null)
                return
            }
            currentTrack.set(track.copy(resolvedUrl = streamUrl))

            val upstreamReq = Request.Builder()
                .url(streamUrl)
                .header("User-Agent",
                    "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip")
                .header("Accept", "*/*")
                .apply { if (rangeHeader != null) header("Range", rangeHeader) }
                .build()

            http.newCall(upstreamReq).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    respond(client, "HTTP/1.1 ${resp.code} Upstream Error", emptyMap(), null)
                    return
                }

                val status        = if (resp.code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                // Use the track's declared mimeType for the response — don't forward
                // YouTube's Content-Type verbatim because it sometimes includes codec
                // parameters (e.g. "audio/mp4; codecs=mp4a.40.2") that confuse Sonos.
                val contentType   = track.mimeType
                val contentLength = resp.header("Content-Length")
                val contentRange  = resp.header("Content-Range")

                val headers = buildMap {
                    put("Content-Type", contentType)
                    if (contentLength != null) put("Content-Length", contentLength)
                    if (contentRange  != null) put("Content-Range",  contentRange)
                    put("Accept-Ranges", "bytes")
                    put("Connection",    "close")
                }

                respond(client, status, headers, resp.body?.byteStream())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handler error: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun respond(
        client: Socket,
        statusLine: String,
        headers: Map<String, String>,
        body: java.io.InputStream?,
    ) {
        try {
            val out = client.getOutputStream()
            val sb  = StringBuilder()
            sb.append("$statusLine\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (body != null) {
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (body.read(buf).also { n = it } >= 0) out.write(buf, 0, n)
            }
            out.flush()
        } catch (_: Exception) { }
    }
}
