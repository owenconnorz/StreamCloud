package com.streamcloud.app.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
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
 * HLS segment rewriting:
 *  - For .m3u8 manifests, all segment and sub-manifest URLs are rewritten to go through
 *    this proxy via ?url=ENCODED_URL query parameters, so every segment also benefits
 *    from the phone's IP and custom headers.
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

    /** Content-type detected from the upstream URL (HEAD probe), or null if unknown. */
    @Volatile var detectedContentType: String? = null

    /**
     * Android application context — required when serving local content:// or file:// URIs.
     * Set by [rememberCastController] before calling [start] whenever the stream URL is local.
     */
    @Volatile var appContext: Context? = null

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
        detectedContentType = null

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

        // Probe content type in background so we know if this is HLS/DASH/MP4.
        // For local content:// / file:// URIs use ContentResolver instead of HTTP HEAD.
        pool.submit {
            detectedContentType = when {
                realUrl.startsWith("content://") || realUrl.startsWith("file://") ->
                    appContext?.contentResolver?.getType(Uri.parse(realUrl))
                        ?.substringBefore(';')?.trim()
                else -> probeContentType(realUrl, headers)
            }
            Log.d(TAG, "Detected content type: $detectedContentType")
        }

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
        detectedContentType = null
        Log.d(TAG, "Proxy stopped")
    }

    private fun probeContentType(url: String, headers: Map<String, String>): String? {
        return try {
            val reqBuilder = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val resp = http.newCall(reqBuilder.build()).execute()
            val ct = resp.header("Content-Type")
            resp.close()
            ct?.substringBefore(';')?.trim()
        } catch (_: Exception) { null }
    }

    /**
     * Serves a local content:// or file:// URI directly from storage with full
     * HTTP range-request support so Chromecast can seek through the file.
     */
    private fun serveLocalFile(output: java.io.OutputStream, uri: String, rangeHeader: String?) {
        val ctx = appContext ?: run {
            output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        try {
            val parsedUri = Uri.parse(uri)
            val mimeType = ctx.contentResolver.getType(parsedUri)
                ?.substringBefore(';')?.trim() ?: "video/mp4"

            val pfd = ctx.contentResolver.openFileDescriptor(parsedUri, "r") ?: run {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            pfd.use { descriptor ->
                val fileSize = descriptor.statSize

                // Parse Range header: "bytes=START-END"
                var start = 0L
                var end = fileSize - 1
                if (rangeHeader != null && fileSize > 0) {
                    Regex("""bytes=(\d*)-(\d*)""").find(rangeHeader)?.let { m ->
                        val s = m.groupValues[1]; val e = m.groupValues[2]
                        if (s.isNotEmpty()) start = s.toLong()
                        end = if (e.isNotEmpty()) minOf(e.toLong(), fileSize - 1) else fileSize - 1
                    }
                }
                val contentLength = end - start + 1
                val isRange = rangeHeader != null && fileSize > 0

                val sb = StringBuilder()
                if (isRange) {
                    sb.append("HTTP/1.1 206 Partial Content\r\n")
                    sb.append("Content-Range: bytes $start-$end/$fileSize\r\n")
                } else {
                    sb.append("HTTP/1.1 200 OK\r\n")
                }
                sb.append("Content-Type: $mimeType\r\n")
                sb.append("Content-Length: $contentLength\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Access-Control-Allow-Origin: *\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                output.flush()

                java.io.FileInputStream(descriptor.fileDescriptor).use { fis ->
                    if (start > 0) fis.skip(start)
                    val buf = ByteArray(BUFFER)
                    var remaining = contentLength
                    var n: Int
                    while (remaining > 0 &&
                        fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            .also { n = it } != -1) {
                        output.write(buf, 0, n)
                        remaining -= n
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "serveLocalFile error for $uri", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            Log.v(TAG, "← $requestLine")

            // Parse path: "GET /path?url=ENCODED HTTP/1.1"
            val rawPath = requestLine.substringAfter(' ').substringBefore(' ')
            val fetchUrl = extractFetchUrl(rawPath)

            var rangeHeader: String? = null
            var line = input.readLine()
            while (!line.isNullOrBlank()) {
                val lower = line.lowercase()
                if (lower.startsWith("range:")) rangeHeader = line.substringAfter(":").trim()
                line = input.readLine()
            }

            // Local content:// or file:// URIs are served directly from storage — OkHttp can't open them.
            if (fetchUrl.startsWith("content://") || fetchUrl.startsWith("file://")) {
                serveLocalFile(output, fetchUrl, rangeHeader)
                return
            }

            val reqBuilder = Request.Builder().url(fetchUrl).get()
            targetHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (rangeHeader != null) {
                reqBuilder.header("Range", rangeHeader)
                Log.v(TAG, "→ Range: $rangeHeader")
            }

            val response = http.newCall(reqBuilder.build()).execute()
            Log.d(TAG, "← ${response.code} (${fetchUrl.take(80)})")

            val responseContentType = response.header("Content-Type", "").orEmpty()
            val isM3u8 = responseContentType.lowercase().let {
                it.contains("mpegurl") || it.contains("x-mpegurl")
            } || fetchUrl.substringBefore("?").lowercase().endsWith(".m3u8")

            if (isM3u8 && rangeHeader == null) {
                // Read manifest, rewrite segment URLs through this proxy, send back
                val body = response.body?.string() ?: ""
                response.close()
                val rewritten = rewriteM3u8(body, fetchUrl)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)

                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/x-mpegURL\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                output.write(bytes)
                output.flush()
            } else {
                // Binary passthrough (MP4, TS segments, keys, DASH, etc.)
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
                for (i in 0 until response.headers.size) {
                    val name = response.headers.name(i)
                    if (name.lowercase() in SKIP_HEADERS) continue
                    sb.append("${name}: ${response.headers.value(i)}\r\n")
                }
                sb.append("Access-Control-Allow-Origin: *\r\n")
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
                } ?: response.close()
            }
        } catch (_: Exception) {
            // Client disconnected early (Chromecast seeked or timed out) — normal
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Extracts the real URL to fetch for this proxy request.
     * Path can be:
     *   "/"                       → fetch targetUrl
     *   "/?url=ENCODED_URL"       → fetch the decoded URL (for HLS segments)
     */
    private fun extractFetchUrl(rawPath: String): String {
        val query = rawPath.substringAfter('?', "")
        if (query.isNotBlank()) {
            query.split('&').forEach { param ->
                if (param.startsWith("url=")) {
                    val encoded = param.substringAfter("url=")
                    return try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Exception) { targetUrl }
                }
            }
        }
        return targetUrl
    }

    /**
     * Rewrites an m3u8 manifest so every segment/sub-manifest URI is routed
     * through this proxy as `http://PROXY_IP:PORT?url=ENCODED_ABSOLUTE_URI`.
     *
     * Handles:
     *  - Absolute URIs (https://...)
     *  - Relative URIs (resolved against the manifest base URL)
     *  - #EXT-X-KEY:...,URI="..." encryption key URIs
     *  - #EXT-X-MAP:URI="..." init segments
     *  - #EXT-X-MEDIA:...,URI="..." alternate rendition manifests
     */
    private fun rewriteM3u8(manifest: String, baseUrl: String): String {
        val proxyBase = currentProxyUrl ?: return manifest
        val base = try { java.net.URL(baseUrl) } catch (_: Exception) { return manifest }

        return manifest.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-KEY") ||
                line.startsWith("#EXT-X-MAP") ||
                line.startsWith("#EXT-X-MEDIA") ||
                line.startsWith("#EXT-X-SESSION-DATA") ->
                    rewriteTagUri(line, base, proxyBase)

                line.startsWith("#") || line.isBlank() -> line

                else -> {
                    // Bare URI line — segment or sub-manifest
                    val absolute = resolveUrl(line.trim(), base)
                    "$proxyBase?url=${URLEncoder.encode(absolute, "UTF-8")}"
                }
            }
        }
    }

    /** Rewrites URI="..." attributes within a tag line. */
    private fun rewriteTagUri(line: String, base: java.net.URL, proxyBase: String): String {
        return line.replace(Regex("""URI="([^"]+)"""")) { match ->
            val uri = match.groupValues[1]
            val absolute = resolveUrl(uri, base)
            """URI="${proxyBase}?url=${URLEncoder.encode(absolute, "UTF-8")}""""
        }
    }

    private fun resolveUrl(uri: String, base: java.net.URL): String {
        return if (uri.startsWith("http://") || uri.startsWith("https://")) uri
        else try { java.net.URL(base, uri).toString() } catch (_: Exception) { uri }
    }

    private val SKIP_HEADERS = setOf(
        "connection", "keep-alive", "transfer-encoding",
        "proxy-connection", "te", "trailer", "upgrade",
    )

    /**
     * Returns the phone's local IPv4 address that the Chromecast can reach.
     * Prefers WiFi (wlan) > Ethernet > other interfaces, and rejects loopback/link-local.
     */
    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { iface -> !iface.isLoopback && iface.isUp }
            ?.sortedByDescending { iface ->
                when {
                    iface.name.startsWith("wlan") -> 3
                    iface.name.startsWith("eth")  -> 2
                    iface.name.startsWith("ap")   -> 1  // AP/hotspot — skip
                    else -> 0
                }
            }
            ?.flatMap { iface -> iface.inetAddresses.asSequence().map { addr -> addr to iface } }
            ?.filter { (addr, iface) ->
                addr is Inet4Address &&
                !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                !iface.name.startsWith("ap") &&  // skip hotspot interface
                addr.hostAddress != null
            }
            ?.firstOrNull()
            ?.first
            ?.hostAddress
    } catch (_: Exception) { null }
}
