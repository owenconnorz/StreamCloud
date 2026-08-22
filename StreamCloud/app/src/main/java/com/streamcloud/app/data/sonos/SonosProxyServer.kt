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
    const val DEFAULT_UPSTREAM_USER_AGENT =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip"

    data class TrackInfo(
        val videoId: String,
        val title: String,
        val watchUrl: String,
        val resolvedUrl: String? = null,
        val mimeType: String = "audio/mp4",
        val userAgent: String = DEFAULT_UPSTREAM_USER_AGENT,
        /** The complete stream size confirmed by the upstream CDN. */
        val contentLength: Long? = null,
        /** Whether the verified source honored a byte-range probe. */
        val supportsRanges: Boolean = false,
    )

    sealed interface TrackPreflight {
        data class Ready(val track: TrackInfo) : TrackPreflight
        data class Failed(val message: String) : TrackPreflight
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentTrack = AtomicReference<TrackInfo?>(null)
    private val latestUpstreamFailure = AtomicReference<String?>(null)
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

    private val probeHttp = http.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun setTrack(info: TrackInfo) {
        currentTrack.set(info)
        latestUpstreamFailure.set(null)
    }

    /** Returns and clears the latest upstream error observed while Sonos was fetching audio. */
    fun consumeUpstreamFailure(): String? = latestUpstreamFailure.getAndSet(null)

    fun start(localIp: String): String {
        stop()
        val ss = ServerSocket(0)
        serverSocket = ss
        port = ss.localPort
        // Use a .m4a extension so Sonos firmware that validates the URI path by file
        // extension (common on S1/older S2) accepts it as a known audio format.
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
        latestUpstreamFailure.set(null)
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

    /**
     * A stream URL can pass the local preflight and still be rejected by the CDN on Sonos's
     * subsequent request (for example when the signed URL expires or a CDN edge rejects the
     * requested range). Resolve one fresh URL so a transient 403/416 does not stop an otherwise
     * healthy Sonos session.
     */
    private fun refreshTrack(track: TrackInfo): TrackInfo? = runBlocking {
        val formatInfo = if (track.videoId.isNotBlank()) {
            runCatching {
                YtPlayerUtils.resolveAudioFormatInfo(track.videoId, sonosSafe = true)
            }.getOrNull()?.takeUnless { it.requiresWebSessionHeaders }
        } else {
            null
        }
        if (formatInfo != null) {
            return@runBlocking track.copy(
                resolvedUrl = formatInfo.url,
                mimeType = formatInfo.mimeType.substringBefore(";").trim(),
                userAgent = formatInfo.userAgent,
                contentLength = formatInfo.contentLength ?: track.contentLength,
            )
        }

        if (track.watchUrl.isBlank()) return@runBlocking null
        runCatching {
            NewPipeRepository.resolveVerifiedAudioStream(track.watchUrl)?.let { extracted ->
                track.copy(
                    resolvedUrl = extracted.url,
                    userAgent = extracted.userAgent,
                    contentLength = track.contentLength,
                )
            }
        }.getOrNull()
    }

    private fun openUpstream(track: TrackInfo, rangeHeader: String?): okhttp3.Response {
        val streamUrl = track.resolvedUrl ?: resolveStreamUrl(track)
            ?: error("No playable audio URL was available.")
        val reqBuilder = Request.Builder()
            .url(streamUrl)
            // CDN signatures are tied to the client that minted the URL. Reusing a generic
            // YouTube Music identity makes otherwise valid PipePipe/InnerTube streams fail
            // only after Sonos has accepted the local proxy URI.
            .header("User-Agent", track.userAgent)
            .header("Accept", "*/*")
        if (rangeHeader != null) {
            reqBuilder.header("Range", rangeHeader)
        }
        return http.newCall(reqBuilder.build()).execute()
    }

    /**
     * Confirm that the URL can provide real audio bytes from this phone before Sonos receives the
     * local proxy URI.  A synthetic successful HEAD response lets Sonos accept a track that will
     * fail as soon as it requests the first byte, leaving its UI at 0:00 with no useful error.
     */
    fun preflight(track: TrackInfo): TrackPreflight {
        val streamUrl = resolveStreamUrl(track)
            ?: return TrackPreflight.Failed("No playable audio URL was available.")

        return try {
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", track.userAgent)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-1")
                .build()

            probeHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return TrackPreflight.Failed(
                        "The audio source rejected the stream (HTTP ${response.code}).",
                    )
                }

                // Reading a byte proves this was not merely a successful header response.
                val firstByte = response.body?.byteStream()?.use { it.read() } ?: -1
                if (firstByte < 0) {
                    return TrackPreflight.Failed("The audio source returned an empty stream.")
                }

                val contentType = response.header("Content-Type")
                    ?.substringBefore(";")
                    ?.trim()
                    ?.takeIf { it.startsWith("audio/") }
                    ?: return TrackPreflight.Failed(
                        "The audio source returned an unsupported content type.",
                    )
                if (contentType !in SONOS_AUDIO_MIME_TYPES) {
                    return TrackPreflight.Failed(
                        "This song is served as $contentType, which this Sonos proxy cannot play.",
                    )
                }

                // A 206 response's Content-Length is only the selected two-byte probe. Its
                // Content-Range carries the actual total; if it is absent, use trusted resolver
                // metadata rather than incorrectly publishing "2" as the whole song.
                val contentLength = fullLengthFromProbe(
                    statusCode = response.code,
                    contentRange = response.header("Content-Range"),
                    contentLength = response.header("Content-Length"),
                    declaredLength = track.contentLength ?: contentLengthFromUrl(streamUrl),
                )
                    ?: return TrackPreflight.Failed(
                        "The audio source did not provide the track length required by Sonos.",
                    )
                if (contentLength <= 0L) {
                    return TrackPreflight.Failed("The audio source returned an invalid track length.")
                }

                Log.d(
                    TAG,
                    "Preflight ready for ${track.videoId}: type=$contentType length=$contentLength",
                )
                TrackPreflight.Ready(
                    track.copy(
                        resolvedUrl = streamUrl,
                        mimeType = contentType,
                        contentLength = contentLength,
                        supportsRanges = supportsByteRanges(
                            statusCode = response.code,
                            contentRange = response.header("Content-Range"),
                        ),
                    ),
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Preflight failed for ${track.videoId}: ${error.message}")
            TrackPreflight.Failed(
                "Could not fetch audio from the source (${error.javaClass.simpleName}).",
            )
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
                val contentLength = track.contentLength
                if (contentLength == null || contentLength <= 0L) {
                    Log.w(TAG, "Rejected HEAD before a verified stream was available")
                    client.getOutputStream()
                        .write("HTTP/1.1 503 Stream Not Ready\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                client.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${track.mimeType}\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    (if (track.supportsRanges) "Accept-Ranges: bytes\r\n" else "") +
                    "Connection: close\r\n" +
                    "\r\n").toByteArray(),
                )
                return
            }

            // Forward Range header from Sonos so partial-content/seeking works end-to-end.
            val rangeHeader = reqHeaders["range"]

            if (resolveStreamUrl(track) == null) {
                Log.w(TAG, "Could not resolve stream for ${track.videoId}")
                reportUpstreamFailure("The audio source could not resolve a playable stream.")
                client.getOutputStream()
                    .write("HTTP/1.1 502 Stream Resolve Failed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }

            var requestTrack = track
            var upstreamResponse = try {
                openUpstream(requestTrack, rangeHeader)
            } catch (error: Exception) {
                val message = "The audio source connection failed (${error.javaClass.simpleName})."
                reportUpstreamFailure(message)
                client.getOutputStream()
                    .write("HTTP/1.1 502 Upstream Connection Failed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }

            // A signed URL may be accepted by the preflight but rejected by the actual Sonos
            // request. Refresh it once instead of reporting a permanent cast failure. Do not
            // retry indefinitely: a persistent CDN rejection must remain visible to the user.
            if (!upstreamResponse.isSuccessful &&
                upstreamResponse.code in setOf(403, 416) &&
                requestTrack.videoId.isNotBlank()
            ) {
                upstreamResponse.close()
                val refreshed = refreshTrack(requestTrack)
                if (refreshed != null) {
                    requestTrack = refreshed
                    currentTrack.set(refreshed)
                    upstreamResponse = try {
                        openUpstream(requestTrack, rangeHeader)
                    } catch (error: Exception) {
                        val message =
                            "The refreshed audio source connection failed (${error.javaClass.simpleName})."
                        reportUpstreamFailure(message)
                        client.getOutputStream()
                            .write("HTTP/502 Refreshed Upstream Failed\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                }
            }

            currentTrack.set(requestTrack)
            upstreamResponse.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "CDN error HTTP ${resp.code} for ${requestTrack.videoId}")
                    reportUpstreamFailure("The audio source rejected playback (HTTP ${resp.code}).")
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
                if (track.supportsRanges) sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")
                out.write(sb.toString().toByteArray())

                val input = resp.body?.byteStream()
                if (input == null) {
                    reportUpstreamFailure("The audio source returned no audio body.")
                    return
                }
                input.use {
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (true) {
                        n = try {
                            input.read(buf)
                        } catch (error: Exception) {
                            reportUpstreamFailure(
                                "Audio delivery stopped while reading the source " +
                                    "(${error.javaClass.simpleName}).",
                            )
                            return
                        }
                        if (n < 0) break
                        try {
                            out.write(buf, 0, n)
                        } catch (error: Exception) {
                            // The speaker may close a completed/abandoned request; this is not an
                            // upstream stream failure and should not make the cast UI show an error.
                            Log.d(TAG, "Sonos client closed stream: ${error.message}")
                            return
                        }
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

    private fun reportUpstreamFailure(message: String) {
        latestUpstreamFailure.set(message)
        Log.w(TAG, message)
    }

    private val SONOS_AUDIO_MIME_TYPES = setOf(
        "audio/aac",
        "audio/aacp",
        "audio/flac",
        "audio/mp4",
        "audio/mpeg",
        "audio/ogg",
        "audio/wav",
        "audio/x-wav",
    )

    private fun contentLengthFromUrl(url: String): Long? =
        Regex("[?&]clen=([0-9]+)")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
}

/**
 * For a ranged response, Content-Length is only the size of the selected range. Sonos needs the
 * full stream size, which is the final number in Content-Range.
 */
internal fun upstreamContentLength(contentRange: String?, contentLength: String?): Long? =
    contentRange
        ?.substringAfterLast("/", missingDelimiterValue = "")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: contentLength?.trim()?.toLongOrNull()?.takeIf { it > 0L }

internal fun fullLengthFromProbe(
    statusCode: Int,
    contentRange: String?,
    contentLength: String?,
    declaredLength: Long?,
): Long? = upstreamContentLength(
    contentRange = contentRange,
    // A 206 Content-Length only describes the small probe range, never the full stream.
    contentLength = contentLength?.takeIf { statusCode != 206 },
) ?: declaredLength?.takeIf { it > 0L }

internal fun supportsByteRanges(statusCode: Int, contentRange: String?): Boolean =
    statusCode == 206 && contentRange?.startsWith("bytes ", ignoreCase = true) == true
