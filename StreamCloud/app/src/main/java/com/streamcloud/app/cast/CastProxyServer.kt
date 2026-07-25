package com.streamcloud.app.cast

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lightweight HTTP reverse-proxy that runs on the phone's local network IP.
 *
 * Why it's needed for casting:
 *  - Debrid links (Real-Debrid, AllDebrid, etc.) are IP-restricted to the device that
 *    requested them.  When the Chromecast tries to fetch the URL directly it uses its own
 *    IP address → the CDN returns a 403/empty response.
 *  - Many stream URLs require custom HTTP headers (Referer, User-Agent, Origin) that the
 *    Cast default receiver cannot send.
 *
 * The proxy listens on 0.0.0.0 (phone's WiFi IP is reachable by the Chromecast since both
 * are on the same LAN).  The Chromecast fetches from the proxy → the proxy fetches the real
 * URL from the phone's IP with the correct headers → streams the bytes back.
 *
 * Range requests (needed for seeking) are transparently forwarded.
 */
object CastProxyServer {
    private const val TAG = "CastProxyServer"
    private const val BUFFER = 65_536

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile var targetUrl: String = ""
    @Volatile var targetHeaders: Map<String, String> = emptyMap()

    /** The http://IP:PORT base the Chromecast should fetch from, or null if proxy isn't running. */
    @Volatile var currentProxyUrl: String? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Start the proxy for [realUrl] with [headers].
     * Returns the proxy base URL ("http://PHONE_IP:PORT") or null if the WiFi IP
     * can't be determined (e.g. no WiFi connection).
     */
    fun start(realUrl: String, headers: Map<String, String>): String? {
        stop()
        if (realUrl.isBlank()) return null

        targetUrl = realUrl
        targetHeaders = headers

        val ip = localIp() ?: run {
            Log.w(TAG, "Could not determine local WiFi IP — proxy unavailable")
            return null
        }

        val ss = try {
            ServerSocket(0, 50)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open ServerSocket", e)
            return null
        }
        serverSocket = ss

        val proxyUrl = "http://$ip:${ss.localPort}"
        currentProxyUrl = proxyUrl
        Log.d(TAG, "Proxy started: $proxyUrl → $realUrl")

        acceptThread = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    pool.submit { handleClient(client) }
                } catch (_: IOException) {
                    break
                }
            }
            Log.d(TAG, "Proxy accept loop ended")
        }.apply {
            isDaemon = true
            name = "CastProxy-Accept"
            start()
        }

        return proxyUrl
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        currentProxyUrl = null
        Log.d(TAG, "Proxy stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            Log.v(TAG, "← $requestLine")

            var rangeHeader: String? = null
            var line = input.readLine()
            while (!line.isNullOrBlank()) {
                val lower = line.lowercase()
                if (lower.startsWith("range:")) rangeHeader = line.substringAfter(":").trim()
                line = input.readLine()
            }

            val reqBuilder = Request.Builder().url(targetUrl).get()
            targetHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (rangeHeader != null) {
                reqBuilder.header("Range", rangeHeader)
                Log.v(TAG, "→ Range: $rangeHeader")
            }

            val response = http.newCall(reqBuilder.build()).execute()
            Log.d(TAG, "← ${response.code} (${targetUrl.take(80)})")

            val sb = StringBuilder()
            sb.append("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")

            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                if (name.lowercase() in SKIP_HEADERS) continue
                sb.append("${name}: ${response.headers.value(i)}\r\n")
            }
            sb.append("Connection: close\r\n")
            sb.append("\r\n")

            output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()

            response.body?.byteStream()?.use { body ->
                val buf = ByteArray(BUFFER)
                var n: Int
                while (body.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        } catch (_: Exception) {
            // Client disconnected early (Chromecast seeked or timed out) — normal
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private val SKIP_HEADERS = setOf(
        "connection", "keep-alive", "transfer-encoding",
        "proxy-connection", "te", "trailer", "upgrade",
    )

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.filter { !it.isLoopbackAddress && it.hostAddress != null }
            ?.firstOrNull()
            ?.hostAddress
    } catch (_: Exception) { null }
}
