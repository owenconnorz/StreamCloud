package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.AppLogger
import com.streamcloud.app.data.newpipe.NewPipeRepository
import com.streamcloud.app.data.ytmusic.potoken.PoTokenGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object YtPlayerUtils {

    private const val TAG = "YtPlayerUtils"

    private data class ClientConfig(
        val label: String,
        val playerUrl: String,
        val clientName: String,
        val clientId: String,
        val clientVersion: String,
        val userAgent: String,
        val extraClientFields: Map<String, Any> = emptyMap(),
        val embedUrlTemplate: String? = null,
        val requiresAuth: Boolean = false,
        // supportsAuth: send the YTM cookie (correct for Android/iOS music clients).
        val supportsAuth: Boolean = true,
        // useWebAuth: also send the SAPISIDHASH Authorization header. This is a
        // browser/web mechanism — Android and iOS app clients reject it with HTTP 400.
        // Only WEB_REMIX (and similar web clients) should set this to true.
        val useWebAuth: Boolean = false,
        val useWebPoTokens: Boolean = false,
        // useSignatureTimestamp: include the signatureTimestamp (sts) from the player JS in the
        // player request body under playbackContext.contentPlaybackContext.signatureTimestamp.
        // Required by MOBILE (ANDROID clientId=3) — without it YouTube returns cipher-only stream
        // formats instead of plain CDN URLs.  The sts value is extracted by YtNSigDescrambler
        // alongside the nsig function.
        val useSignatureTimestamp: Boolean = false,
    )

    private val CLIENTS = listOf(
        // This is the same active main/fallback client family and order as Metrolist. The
        // client-specific StreamCloud request settings keep the resolver identity intact through
        // the CDN request and its one-shot recovery path.
        ClientConfig(
            label                 = "WEB_REMIX",
            playerUrl             = "https://music.youtube.com/youtubei/v1/player?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-KLET5YdUo&prettyPrint=false",
            clientName            = "WEB_REMIX",
            clientId              = "67",
            clientVersion         = "1.20260213.01.00",
            userAgent             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            useWebAuth            = true,
            useWebPoTokens        = true,
            useSignatureTimestamp = true,
        ),

        ClientConfig(
            label         = "VISIONOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "VISIONOS",
            clientId      = "101",
            clientVersion = "0.1",
            userAgent     = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            extraClientFields = mapOf(
                "osName"      to "visionOS",
                "osVersion"   to "1.3.21O771",
                "deviceMake"  to "Apple",
                "deviceModel" to "RealityDevice14,1",
            ),
            supportsAuth = false,
        ),

        ClientConfig(
            label                 = "WEB_CREATOR",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "WEB_CREATOR",
            clientId              = "62",
            clientVersion         = "1.20260213.00.00",
            userAgent             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            requiresAuth          = true,
            useWebAuth            = true,
            useWebPoTokens        = true,
            useSignatureTimestamp = true,
        ),

        ClientConfig(
            label                 = "TVHTML5",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "TVHTML5",
            clientId              = "7",
            clientVersion         = "7.20260213.00.00",
            userAgent             = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            requiresAuth          = true,
            useWebPoTokens        = true,
            useSignatureTimestamp = true,
        ),

        ClientConfig(
            label         = "ANDROID_VR_1_43",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_VR",
            clientId      = "28",
            clientVersion = "1.43.32",
            userAgent     = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "12",
                "deviceMake"        to "Oculus",
                "deviceModel"       to "Quest 3",
                "androidSdkVersion" to "32",
                "buildId"           to "SQ3A.220605.009.A1",
                "cronetVersion"     to "107.0.5284.2",
                "packageName"       to "com.google.android.apps.youtube.vr.oculus",
            ),
            supportsAuth  = false,
        ),

        ClientConfig(
            label         = "ANDROID_VR_1_61",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_VR",
            clientId      = "28",
            clientVersion = "1.61.48",
            userAgent     = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "12",
                "deviceMake"        to "Oculus",
                "deviceModel"       to "Quest 3",
                "androidSdkVersion" to "32",
                "buildId"           to "SQ3A.220605.009.A1",
                "cronetVersion"     to "132.0.6808.3",
                "packageName"       to "com.google.android.apps.youtube.vr.oculus",
            ),
            supportsAuth  = false,
        ),

        ClientConfig(
            label                 = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId              = "85",
            clientVersion         = "2.0",
            userAgent             = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            embedUrlTemplate      = "https://www.youtube.com/embed/%VIDEO_ID%",
            useSignatureTimestamp = true,
        ),

        ClientConfig(
            label         = "IOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "21.03.1",
            userAgent     = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            extraClientFields = mapOf("osVersion" to "18.2.22C152"),
        ),

        ClientConfig(
            label         = "IPADOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "21.03.3",
            userAgent     = "com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
            extraClientFields = mapOf(
                "osName"      to "iPadOS",
                "osVersion"   to "17.7.10.21H450",
                "deviceMake"  to "Apple",
                "deviceModel" to "iPad7,6",
                "packageName" to "com.google.ios.youtube",
            ),
            supportsAuth = false,
        ),

        ClientConfig(
            label         = "ANDROID_CREATOR",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_CREATOR",
            clientId      = "14",
            clientVersion = "25.03.101",
            userAgent     = "com.google.android.apps.youtube.creator/25.03.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "15",
                "deviceMake"        to "Google",
                "deviceModel"       to "Pixel 9 Pro Fold",
                "androidSdkVersion" to "35",
                "buildId"           to "AP3A.241005.015.A2",
                "cronetVersion"     to "132.0.6779.0",
                "packageName"       to "com.google.android.apps.youtube.creator",
            ),
            useSignatureTimestamp = true,
        ),

        ClientConfig(
            label         = "ANDROID_VR_NO_AUTH",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_VR",
            clientId      = "28",
            clientVersion = "1.61.48",
            userAgent     = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            supportsAuth  = false,
        ),

        ClientConfig(
            label                 = "MOBILE",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "ANDROID",
            clientId              = "3",
            clientVersion         = "21.03.38",
            userAgent             = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
            useSignatureTimestamp = true,
        ),

        ClientConfig(
            label                 = "WEB",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "WEB",
            clientId              = "1",
            clientVersion         = "2.20260213.00.00",
            userAgent             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            useWebAuth            = true,
            useSignatureTimestamp = true,
        ),
    )

    // Force IPv4-only DNS so that YouTube's player API sees an IPv4 source IP and embeds
    // an IPv4 address in the CDN URL `ip=` parameter.  The ExoPlayer CDN client
    // (streamOkHttp in MusicPlaybackService) also uses IPv4-only DNS, so the two IPs
    // always match — preventing the empty HTTP 403 caused by an IPv4/IPv6 mismatch.
    private val ipv4OnlyDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            okhttp3.Dns.SYSTEM.lookup(hostname)
                .filter { it is java.net.Inet4Address }
                .ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
    }

    private val http = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val poTokenGenerator = PoTokenGenerator()

    @Volatile var appContext: Context? = null
    @Volatile var ytMusicCookie: String = ""
    @Volatile var contentLanguage: String = "en"
    @Volatile var contentCountry:  String = "US"

    // Public so MusicPlaybackService can include X-Goog-Visitor-Id in CDN requests for PoToken validation.
    @Volatile var cachedVisitorData: String? = null
    @Volatile private var visitorDataFetchedAt: Long = 0L

    private fun ensureVisitorData() {
        val now = System.currentTimeMillis()
        if (cachedVisitorData != null && now - visitorDataFetchedAt < 6 * 3_600_000L) return
        try {
            // Use the current web client version and include an API key — the visitor_id
            // endpoint returns HTTP 4xx without a key on newer YouTube server versions.
            val body = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20260213.00.00")
                        put("hl", contentLanguage)
                        put("gl", contentCountry)
                    }
                }
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20260213.00.00")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.w(TAG, "visitorData: visitor_id HTTP ${resp.code} — trying browse fallback")
                    fetchVisitorDataFromBrowse(now)
                    return
                }
                val text = resp.body?.string() ?: return
                val obj = json.parseToJsonElement(text).jsonObject
                // Top-level visitorData first, then responseContext.visitorData
                val vd = obj["visitorData"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: obj["responseContext"]?.jsonObject?.get("visitorData")
                        ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (vd != null) {
                    cachedVisitorData = vd
                    visitorDataFetchedAt = now
                    AppLogger.i(TAG, "visitorData ready (visitor_id endpoint): ${vd.take(20)}…")
                } else {
                    AppLogger.w(TAG, "visitorData: field missing from visitor_id response — trying browse fallback")
                    fetchVisitorDataFromBrowse(now)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "visitorData fetch failed: ${e.message} — trying browse fallback")
            fetchVisitorDataFromBrowse(System.currentTimeMillis())
        }
    }

    /** Fallback: extract visitorData from a simple YTM browse response. */
    private fun fetchVisitorDataFromBrowse(now: Long) {
        try {
            val body = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20260213.01.00")
                        put("hl", contentLanguage)
                        put("gl", contentCountry)
                    }
                }
                put("browseId", "FEmusic_home")
            }
            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-KLET5YdUo&prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://music.youtube.com")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", "1.20260213.01.00")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.w(TAG, "visitorData: browse fallback HTTP ${resp.code} — WEB_REMIX will be skipped")
                    return
                }
                val text = resp.body?.string() ?: return
                val obj = json.parseToJsonElement(text).jsonObject
                val vd = obj["responseContext"]?.jsonObject?.get("visitorData")
                    ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return
                cachedVisitorData = vd
                visitorDataFetchedAt = now
                AppLogger.i(TAG, "visitorData ready (browse fallback): ${vd.take(20)}…")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "visitorData browse fallback failed: ${e.message} — WEB_REMIX will be skipped")
        }
    }

    data class AudioFormatInfo(
        val url: String,
        val userAgent: String,
        val clientLabel: String,
        /**
         * Web/PoToken stream URLs are tied to a browser session; anonymous app-client URLs are
         * not and must not be mixed with browser cookies.
         */
        val requiresWebSessionHeaders: Boolean,
        val itag: Int,
        val mimeType: String,
        val bitrate: Long,
        val sampleRate: Int?,
        val contentLength: Long?,
        val loudnessDb: Double?,
        val expiresInSeconds: Long,
    )

    data class AudioStreamInfo(
        val url: String,
        val contentLength: Long?,
    )

    /**
     * Prepare all reusable cold-start prerequisites while the application/service is idle.
     *
     * This intentionally does not resolve a real song: signed stream URLs are short lived and
     * must remain tied to the track a listener actually chooses.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        ensureVisitorData()
        YtNSigDescrambler.warmUp()

        val sessionId = cachedVisitorData ?: return@withContext
        val context = appContext ?: return@withContext
        poTokenGenerator.warmUp(
            context = context,
            sessionId = sessionId,
            warmUpVideoId = POTOKEN_WARMUP_VIDEO_ID,
        )
    }

    suspend fun resolveAudioFormatInfo(
        videoId: String,
        preferItag: Int? = null,
        preferHighQuality: Boolean = true,
        sonosSafe: Boolean = false,
        excludedClientLabels: Set<String> = emptySet(),
    ): AudioFormatInfo? = withContext(Dispatchers.IO) {
        val isLoggedIn = ytMusicCookie.isNotBlank()
        var visitorDataPreparedForWebFallback = false

        for (client in CLIENTS.sortedBy { playbackClientPriority(it.label) }) {
            if (client.label in excludedClientLabels) {
                AppLogger.i(TAG, "[${client.label}] skipped — previous stream URL was rejected by the CDN")
                continue
            }
            if (client.requiresAuth && !isLoggedIn) {
                Log.d(TAG, "[${client.label}] skipped — requires auth")
                continue
            }

            // Only PoToken-backed web clients need visitor data. Anonymous fallbacks do not
            // trigger that preparation unless the main WEB_REMIX route has already failed.
            if (client.useWebPoTokens && !visitorDataPreparedForWebFallback) {
                ensureVisitorData()
                visitorDataPreparedForWebFallback = true
            }

            // Generate PoToken for web clients that require it (WEB_REMIX, TVHTML5).
            // We keep the full PoTokenResult so we can later append streamingDataPoToken to
            // the CDN URL — this is REQUIRED: without pot= the CDN always returns 403 for
            // WEB_REMIX streams (mirrors Metrolist YTPlayerUtils.kt line 294–302).
            //
            // sessionId is read inside the loop so a previous client response can bootstrap
            // WEB_REMIX in the same resolution attempt.
            var poTokenResult: com.streamcloud.app.data.ytmusic.potoken.PoTokenResult? = null
            if (client.useWebPoTokens) {
                val sessionId = cachedVisitorData
                if (sessionId == null) {
                    AppLogger.w(TAG, "[${client.label}] skipped — visitorData unavailable (PoToken needs session)")
                    continue
                }
                val ctx = appContext
                if (ctx == null) {
                    AppLogger.w(TAG, "[${client.label}] skipped — app context unavailable for PoToken")
                    continue
                }
                try {
                    poTokenResult = poTokenGenerator.getWebClientPoToken(ctx, videoId, sessionId)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "[${client.label}] PoToken failed: ${e.message}")
                }
                if (poTokenResult == null) {
                    // Without PoToken, WEB_REMIX/TVHTML5 stream URLs always 403 at CDN level
                    // even if the player API returns a valid response.  Skip rather than
                    // caching a URL that ExoPlayer will reject.
                    AppLogger.w(TAG, "[${client.label}] skipped — PoToken unavailable (CDN would 403 without pot=)")
                    continue
                }
                Log.d(TAG, "[${client.label}] PoToken generated ok")
            }

            // For clients that need signatureTimestamp (sts) in the player request body,
            // ensure the player JS has been fetched and sts extracted before building the request.
            // This matches Metrolist's getSignatureTimestampOrNull() being called before any player
            // request in playerResponseForPlayback().  After the first fetch the warmUp() is a
            // no-op (guarded by snippetFetchedAt TTL), so there is zero latency on warm paths.
            if (client.useSignatureTimestamp) {
                YtNSigDescrambler.warmUp()
            }

            val result = tryClient(client, videoId, preferItag, preferHighQuality, poTokenResult?.playerRequestPoToken, sonosSafe)
            when (result) {
                is ClientResult.Success -> {
                    // Apply n-transform for clients that need it — same set as Metrolist's
                    // needsNTransform check in YTPlayerUtils.playerResponseForPlayback():
                    //   currentClient.useWebPoTokens ||
                    //   currentClient.clientName in listOf("WEB","WEB_REMIX","WEB_CREATOR","TVHTML5")
                    //
                    // Critically we do NOT skip when transform fails.  Metrolist never skips on
                    // descramble failure — it applies best-effort and lets validateStatus() decide.
                    // For the May 2026 player (57f5d44f) nsig extraction returns empty-string from
                    // the hardcoded config, so descrambleUrl() returns the original URL unchanged.
                    // The CDN accepts those URLs directly (no n-param enforcement for this player),
                    // so WEB_REMIX and WEB_CREATOR work perfectly without any transformation.
                    // Our previous "skip when n-descramble fails" logic incorrectly discarded those
                    // valid URLs, which is why every track was failing all clients.
                    val needsNDescramble = client.useWebPoTokens ||
                        client.clientName in setOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")
                    val afterNDescramble = if (needsNDescramble) {
                        YtNSigDescrambler.descrambleUrl(result.info.url)
                    } else {
                        result.info.url
                    }
                    val nDescrambled = afterNDescramble != result.info.url

                    // Append streaming PoToken (pot=) to CDN URL — CRITICAL for WEB_REMIX/TVHTML5.
                    // Without pot= the CDN always returns 403, even when playerRequestPoToken was
                    // included in the player API request body.
                    // Mirrors Metrolist YTPlayerUtils.kt lines 293–302.
                    val candidateUrl = if (client.useWebPoTokens && poTokenResult?.streamingDataPoToken != null) {
                        val sep = if ("?" in afterNDescramble) "&" else "?"
                        // YouTube player JS appends BOTH potc=1 AND pot=<token> together:
                        //   var Q={potc:"1",pot:D}; E.url&&(E.url=jk(E.url,Q))
                        // The CDN validates that potc= is present alongside pot= — omitting
                        // potc causes a 403 even when the PoToken itself is cryptographically valid.
                        "${afterNDescramble}${sep}potc=1&pot=${java.net.URLEncoder.encode(poTokenResult.streamingDataPoToken, "UTF-8")}"
                            .also { AppLogger.i(TAG, "[${client.label}] $videoId — potc=1&pot= appended to stream URL") }
                    } else {
                        afterNDescramble
                    }

                    AppLogger.i(TAG, "[${client.label}] resolved $videoId → itag=${result.info.itag} n-descrambled=$nDescrambled")

                    // Validate fallback candidates with a HEAD request before committing to a URL.
                    //
                    // Match Metrolist's primary-route behavior: WEB_REMIX goes directly to
                    // ExoPlayer and its first byte-range read is the health check. A real CDN
                    // rejection is evicted and retried through the independent fallback chain.
                    // Every fallback must prove its first byte-range request is readable with the
                    // same resolver identity that Media3 will use.
                    val requiresWebSessionHeaders =
                        client.useWebAuth || client.useWebPoTokens || client.requiresAuth
                    val skipRangeValidation = client.label == PRIMARY_FAST_START_CLIENT
                    if (skipRangeValidation || validateStreamUrl(
                            url = candidateUrl,
                            userAgent = client.userAgent,
                            requiresWebSessionHeaders = requiresWebSessionHeaders,
                        )
                    ) {
                        if (skipRangeValidation) {
                            AppLogger.i(
                                TAG,
                                "[${client.label}] $videoId — skipping range validation, passing to ExoPlayer",
                            )
                        }
                        return@withContext result.info.copy(
                            url = candidateUrl,
                            requiresWebSessionHeaders = requiresWebSessionHeaders,
                        )
                    } else {
                        AppLogger.w(TAG, "[${client.label}] $videoId — URL failed range validation, trying next client")
                    }
                }
                is ClientResult.CipheredOnly ->
                    AppLogger.w(TAG, "[${client.label}] $videoId — ciphered only, trying next")
                is ClientResult.NoStreams -> {
                    val why = result.reason?.let { " ($it)" } ?: ""
                    AppLogger.w(TAG, "[${client.label}] $videoId — no streams$why, trying next")
                    Log.d(TAG, "[${client.label}] no streams status=${result.status}")
                }
                is ClientResult.Error ->
                    AppLogger.w(TAG, "[${client.label}] $videoId — error: ${result.cause?.message}")
            }
        }
        AppLogger.e(TAG, "All clients failed for $videoId")
        throw IllegalStateException("YouTube returned no audio streams for $videoId")
    }

    suspend fun resolveAudioStreamInfo(videoId: String): AudioStreamInfo? =
        resolveAudioFormatInfo(videoId)?.let { AudioStreamInfo(it.url, it.contentLength) }

    suspend fun resolveAudioStream(videoId: String, sonosSafe: Boolean = false): String? =
        resolveAudioFormatInfo(videoId, sonosSafe = sonosSafe)?.url

    private fun playbackClientPriority(label: String): Int = when (label) {
        "WEB_REMIX"                       -> 0
        "VISIONOS"                        -> 1
        "WEB_CREATOR"                     -> 2
        "TVHTML5"                         -> 3
        "ANDROID_VR_1_43"                -> 4
        "ANDROID_VR_1_61"                -> 5
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> 6
        "IOS"                             -> 7
        "IPADOS"                          -> 8
        "ANDROID_CREATOR"                 -> 9
        "ANDROID_VR_NO_AUTH"             -> 10
        "MOBILE"                          -> 11
        "WEB"                             -> 12
        else                              -> 50
    }

    private const val PRIMARY_FAST_START_CLIENT = "WEB_REMIX"
    private const val POTOKEN_WARMUP_VIDEO_ID = "jNQXAC9IVRw"

    // ── Music video detection + stream resolution ─────────────────────────────────────────────

    data class VideoStreamResult(
        /** True when the YouTube item exposes a playable visual track, not just audio. */
        val isMusicVideo: Boolean,
        /** Best MP4 visual URL (720p preferred), or null if unresolvable. */
        val url: String?,
        /** Must match the client that generated [url] so the CDN accepts the video request. */
        val userAgent: String? = null,
    )

    /**
     * Determines whether [videoId] is a proper music video and, if so, resolves the best
     * MP4 visual stream. A muxed stream is preferred, but a video-only adaptive MP4 works too:
     * the visual player is muted and stays synced to the primary audio player.
     *
     * Detection heuristic: audio-only tracks expose no `video/mp4` format. Modern YouTube
     * responses commonly place visual tracks only in `adaptiveFormats[]`, so treating an empty
     * muxed list as audio-only hides valid music videos.
     */
    suspend fun resolveVideoStream(videoId: String): VideoStreamResult = withContext(Dispatchers.IO) {
        try {
            ensureVisitorData()
        } catch (e: Exception) {
            AppLogger.w(TAG, "resolveVideoStream $videoId — visitor data unavailable: ${e.message}")
            return@withContext VideoStreamResult(isMusicVideo = false, url = null)
        }
        var foundVisualTrack = false
        val clients = listOf(
            "VISIONOS",
            "ANDROID_VR_1_43",
            "ANDROID_VR_1_61",
            "ANDROID_VR_NO_AUTH",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        ).mapNotNull { label -> CLIENTS.firstOrNull { it.label == label } }

        for (client in clients) {
            try {
                val root = fetchPlayerResponse(client, videoId, null) ?: continue
                val streamingData = root["streamingData"]?.jsonObject ?: continue

                val muxedFormats = streamingData["formats"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("video/mp4") }
                    ?: emptyList()
                val adaptiveVideoFormats = streamingData["adaptiveFormats"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("video/mp4") }
                    ?: emptyList()

                if (muxedFormats.isEmpty() && adaptiveVideoFormats.isEmpty()) {
                    AppLogger.i(TAG, "resolveVideoStream $videoId via ${client.label} — no visual MP4")
                    continue
                }
                foundVisualTrack = true

                // Prefer a compact muxed stream. If YouTube only exposes DASH video, use the
                // best video-only MP4 at or below 720p; the muted visual player needs no audio.
                val best = muxedFormats.find { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 22 }
                    ?: muxedFormats.find { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 18 }
                    ?: muxedFormats.firstOrNull()
                    ?: adaptiveVideoFormats
                        .filter {
                            it["height"]?.jsonPrimitive?.content?.toIntOrNull()
                                ?.let { height -> height <= 720 }
                                ?: false
                        }
                        .maxByOrNull { it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                    ?: adaptiveVideoFormats
                        .minByOrNull {
                            it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MAX_VALUE
                        }
                    ?: continue

                val rawUrl = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: best["signatureCipher"]?.jsonPrimitive?.content
                        ?.let(::parseCipherUrl)
                    ?: best["cipher"]?.jsonPrimitive?.content
                        ?.let(::parseCipherUrl)
                if (rawUrl.isNullOrBlank()) {
                    AppLogger.w(TAG, "resolveVideoStream $videoId via ${client.label} — visual URL is ciphered")
                    continue
                }

                val cpn = generateCpn()
                val itag = best["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val visualSource = if (best in muxedFormats) "muxed" else "adaptive-video"
                AppLogger.i(
                    TAG,
                    "resolveVideoStream $videoId via ${client.label} — itag=$itag $visualSource ok",
                )
                return@withContext VideoStreamResult(
                    isMusicVideo = true,
                    url = "$rawUrl&cpn=$cpn",
                    userAgent = client.userAgent,
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "resolveVideoStream $videoId via ${client.label} — ${e.message}")
            }
        }

        val extractorStream = runCatching {
            NewPipeRepository.resolveVerifiedVideoStream("https://www.youtube.com/watch?v=$videoId")
        }.onFailure { error ->
            AppLogger.w(TAG, "resolveVideoStream $videoId — extractor fallback failed: ${error.message}")
        }.getOrNull()
        if (extractorStream != null) {
            AppLogger.i(
                TAG,
                "resolveVideoStream $videoId via ${extractorStream.resolverLabel} extractor fallback",
            )
            return@withContext VideoStreamResult(
                isMusicVideo = true,
                url = extractorStream.url,
                userAgent = extractorStream.userAgent,
            )
        }

        VideoStreamResult(isMusicVideo = foundVisualTrack, url = null)
    }

    private val AGE_GATE_STATUSES = setOf(
        "AGE_CHECK_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        "LOGIN_REQUIRED",
        "CONTENT_CHECK_REQUIRED",
    )

    private sealed interface ClientResult {
        data class Success(val info: AudioFormatInfo) : ClientResult
        data object CipheredOnly : ClientResult
        data class NoStreams(val reason: String? = null, val status: String? = null) : ClientResult
        data class Error(val cause: Throwable?) : ClientResult
    }

    private fun tryClient(
        client: ClientConfig,
        videoId: String,
        preferItag: Int?,
        preferHighQuality: Boolean,
        poToken: String?,
        sonosSafe: Boolean = false,
    ): ClientResult {
        return try {
            val root = fetchPlayerResponse(client, videoId, poToken)
                ?: return ClientResult.Error(null)

            val playabilityStatusObj = root["playabilityStatus"]?.jsonObject
            val playabilityReason = playabilityStatusObj?.get("reason")?.jsonPrimitive?.content
            val playabilityStatus = playabilityStatusObj?.get("status")?.jsonPrimitive?.content

            val streamingData = root["streamingData"]?.jsonObject
                ?: return ClientResult.NoStreams(playabilityReason, playabilityStatus)

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray
                ?: return ClientResult.NoStreams(playabilityReason, playabilityStatus)

            val audioOnly = adaptiveFormats
                .mapNotNull { it as? JsonObject }
                .filter { it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/") }

            if (audioOnly.isEmpty()) return ClientResult.NoStreams(playabilityReason, playabilityStatus)

            // Prefer formats with a direct url field; fall back to signatureCipher extraction.
            // As of May 2026 (player hash 57f5d44f) YouTube no longer verifies the cipher
            // signature — the base url= inside signatureCipher is valid without decryption.
            val candidateFormats = run {
                val withDirectUrl = audioOnly.filter {
                    it["url"]?.jsonPrimitive?.content?.isNotBlank() == true
                }
                val pool = withDirectUrl.ifEmpty {
                    audioOnly.filter { fmt ->
                        val cipher = fmt["signatureCipher"]?.jsonPrimitive?.content
                            ?: fmt["cipher"]?.jsonPrimitive?.content
                        cipher != null && parseCipherUrl(cipher) != null
                    }
                }
                if (sonosSafe) {
                    val mp4Only = pool.filter {
                        !it["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
                    }
                    mp4Only.takeIf { it.isNotEmpty() } ?: pool
                } else pool
            }
            if (candidateFormats.isEmpty()) return ClientResult.CipheredOnly

            val expiresInSeconds =
                streamingData["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 21_600L

            val best = if (preferItag != null) {
                candidateFormats.find { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == preferItag }
                    ?: selectHighQuality(candidateFormats)
                    ?: selectByQuality(candidateFormats, preferHighQuality)
            } else {
                selectHighQuality(candidateFormats) ?: selectByQuality(candidateFormats, preferHighQuality)
            }

            val cpn = generateCpn()
            // Get the URL — direct field first, then extract from signatureCipher
            val rawUrl = best["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: run {
                    val cipher = best["signatureCipher"]?.jsonPrimitive?.content
                        ?: best["cipher"]?.jsonPrimitive?.content
                    cipher?.let { parseCipherUrl(it) }
                }
                ?: return ClientResult.CipheredOnly
            val contentLength = best["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()
            // Do NOT append &range= to the URL — YouTube CDN will lock the response to that byte range
            // and ignore ExoPlayer's HTTP Range headers, causing ERROR_CODE_IO_UNSPECIFIED when seeking.
            // ExoPlayer handles range requests via standard Range: bytes=X-Y headers automatically.
            val url = "$rawUrl&cpn=$cpn"

            val loudnessDb = root["playerConfig"]
                ?.jsonObject?.get("audioConfig")
                ?.jsonObject?.get("loudnessDb")
                ?.jsonPrimitive?.content?.toDoubleOrNull()

            ClientResult.Success(
                AudioFormatInfo(
                    url              = url,
                    userAgent        = client.userAgent,
                    clientLabel      = client.label,
                    requiresWebSessionHeaders = false,
                    itag             = best["itag"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    mimeType         = best["mimeType"]?.jsonPrimitive?.content.orEmpty(),
                    bitrate          = best["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    sampleRate       = best["audioSampleRate"]?.jsonPrimitive?.content?.toIntOrNull(),
                    contentLength    = contentLength,
                    loudnessDb       = loudnessDb,
                    expiresInSeconds = expiresInSeconds,
                )
            )
        } catch (e: Exception) {
            ClientResult.Error(e)
        }
    }

    private fun fetchPlayerResponse(
        client: ClientConfig,
        videoId: String,
        poToken: String?,
    ): JsonObject? {
        val embedUrl = client.embedUrlTemplate?.replace("%VIDEO_ID%", videoId)
        val requestOrigin = if (client.playerUrl.contains("music.youtube.com"))
            "https://music.youtube.com" else "https://www.youtube.com"
        val vd = cachedVisitorData

        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("userAgent", client.userAgent)
                    put("hl", contentLanguage)
                    put("gl", contentCountry)
                    if (vd != null) put("visitorData", vd)
                    client.extraClientFields.forEach { (k, v) ->
                        when (v) {
                            is Int     -> put(k, v)
                            is Long    -> put(k, v)
                            is Boolean -> put(k, v)
                            else       -> put(k, v.toString())
                        }
                    }
                }
                if (embedUrl != null) {
                    putJsonObject("thirdParty") {
                        put("embedUrl", embedUrl)
                    }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (poToken != null) {
                putJsonObject("serviceIntegrityDimensions") {
                    put("poToken", poToken)
                }
            }
            // MOBILE (ANDROID clientId=3) needs signatureTimestamp in the player request body.
            // Without it, YouTube returns cipher-only stream formats instead of plain CDN URLs.
            // The sts value is extracted from the same player JS as the nsig function.
            if (client.useSignatureTimestamp) {
                val sts = YtNSigDescrambler.getSignatureTimestamp()
                if (sts != null) {
                    putJsonObject("playbackContext") {
                        putJsonObject("contentPlaybackContext") {
                            put("signatureTimestamp", sts)
                        }
                    }
                } else {
                    AppLogger.w(TAG, "[${client.label}] signatureTimestamp not available — MOBILE may return cipher-only formats")
                }
            }
        }

        val reqBuilder = Request.Builder()
            .url(client.playerUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("X-Goog-Api-Format-Version", "1")
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (vd != null) reqBuilder.header("X-Goog-Visitor-Id", vd)

        val cookie = ytMusicCookie
        if (cookie.isNotBlank() && client.supportsAuth) {
            reqBuilder.header("Cookie", cookie)
            reqBuilder.header("Origin", requestOrigin)
            // SAPISIDHASH is a browser/web auth mechanism. Android and iOS app clients
            // return HTTP 400 when it is present — only send it for web clients (useWebAuth).
            if (client.useWebAuth) {
                val auth = YtMusicAuth.sapisidHashHeader(cookie, requestOrigin)
                if (auth != null) reqBuilder.header("Authorization", auth)
            }
        }

        return http.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "[${client.label}] $videoId — HTTP ${resp.code}")
                return null
            }
            val text = resp.body?.string() ?: return null
            val parsed = json.parseToJsonElement(text).jsonObject
            // Opportunistically cache visitorData from any API response — even "no streams"
            // responses include responseContext.visitorData.  This bootstraps WEB_REMIX in
            // the same resolveAudioFormatInfo() call without a separate fetch.
            if (cachedVisitorData == null) {
                val vd = parsed["responseContext"]?.jsonObject
                    ?.get("visitorData")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (vd != null) {
                    cachedVisitorData = vd
                    visitorDataFetchedAt = System.currentTimeMillis()
                    AppLogger.i(TAG, "[${client.label}] visitorData captured from player response")
                }
            }
            parsed
        }
    }

    private fun selectByQuality(audioFormats: List<JsonObject>, preferHighQuality: Boolean): JsonObject =
        audioFormats.maxByOrNull { fmt ->
            val bitrate = fmt["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val isOpus  = fmt["mimeType"]?.jsonPrimitive?.content.orEmpty().startsWith("audio/webm")
            val sign    = if (preferHighQuality) 1L else -1L
            bitrate * sign + (if (isOpus) 10_240L else 0L)
        } ?: audioFormats.first()

    private fun selectHighQuality(audioFormats: List<JsonObject>): JsonObject? {
        val high = audioFormats.filter {
            it["audioQuality"]?.jsonPrimitive?.content == "AUDIO_QUALITY_HIGH"
        }
        if (high.isEmpty()) return null
        return high.firstOrNull { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 774 }
            ?: high.firstOrNull { it["itag"]?.jsonPrimitive?.content?.toIntOrNull() == 141 }
            ?: high.first()
    }

    /**
     * Validate a stream URL with the same first-byte request shape that Media3 will use.
     *
     * A HEAD request is not sufficient for WEB_REMIX/TVHTML5 URLs: the CDN can accept HEAD and
     * still reject the first ranged media read. The exact resolver identity and browser session
     * must be carried into this probe or a valid session-backed URL is falsely rejected.
     */
    private fun validateStreamUrl(
        url: String,
        userAgent: String,
        requiresWebSessionHeaders: Boolean,
    ): Boolean {
        return try {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-1")
            if (requiresWebSessionHeaders) {
                ytMusicCookie.takeIf { it.isNotBlank() }?.let { cookie ->
                    builder.header("Cookie", cookie)
                        .header("Origin", "https://music.youtube.com")
                        .header("Referer", "https://music.youtube.com/")
                }
                cachedVisitorData?.let { visitor ->
                    builder.header("X-Goog-Visitor-Id", visitor)
                }
                if (url.contains("pot=")) {
                    builder.header("Sec-Fetch-Dest", "audio")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "cross-site")
                }
            }
            http.newCall(builder.build()).execute().use { response ->
                val valid = response.code == 200 || response.code == 206
                if (!valid) {
                    AppLogger.w(TAG, "stream range probe rejected HTTP ${response.code}")
                }
                valid
            }
        } catch (e: Exception) {
            Log.d(TAG, "validateStreamUrl exception (assuming ok): ${e.message}")
            true   // network error ≠ 403 — don't skip a potentially good URL
        }
    }

    /**
     * Extract the base url= value from a YouTube signatureCipher / cipher param string.
     * The cipher is URL-encoded: s=...&sp=sig&url=https%3A%2F%2F...
     * As of May 2026 (player hash 57f5d44f) YouTube stopped enforcing cipher signatures,
     * so using this base URL directly (without decrypting or appending 's') works fine.
     */
    private fun parseCipherUrl(cipher: String): String? {
        for (part in cipher.split("&")) {
            val eqIdx = part.indexOf('=')
            if (eqIdx < 0) continue
            if (part.substring(0, eqIdx) == "url") {
                return java.net.URLDecoder.decode(part.substring(eqIdx + 1), "UTF-8")
                    .takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun generateCpn(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
