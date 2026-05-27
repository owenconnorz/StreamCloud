package com.streamcloud.app.data.ytmusic

import android.content.Context
import android.util.Log
import com.streamcloud.app.data.AppLogger
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

        // ── REMOVAL NOTES ────────────────────────────────────────────────────────
        // ANDROID_MUSIC:  music.youtube.com returns LOGIN_REQUIRED for all unauthenticated
        //                 requests; browser cookies are also rejected (endpoint needs OAuth2).
        // ANDROID_VR:     Returns "Sign in to confirm you're not a bot" on every unauthenticated
        //                 request — bot-detection triggered before IOS even gets a chance.
        // IOS / IPADOS:   Resolve successfully but the CDN returns HTTP 403. YouTube now
        //                 enforces the 'n' parameter for iOS clients; without descrambling
        //                 the obfuscated 'n' value the CDN rejects the byte-fetch entirely.
        //                 ANDROID (id=3) has the same problem.
        // ─────────────────────────────────────────────────────────────────────────

        // #1 ANDROID_TESTSUITE — the only current client whose stream URLs bypass 'n'-parameter
        // enforcement.  YouTube's CDN does not validate 'n' for this internal test client,
        // so the URL is usable directly without JS-based descrambling.
        ClientConfig(
            label         = "ANDROID_TESTSUITE",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_TESTSUITE",
            clientId      = "30",
            clientVersion = "1.9",
            userAgent     = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "11",
                "androidSdkVersion" to "30",
            ),
            supportsAuth = false,
        ),

        // #2 WEB_REMIX — YouTube Music web client with SAPISIDHASH + PoToken.
        // useSignatureTimestamp=true matches Metrolist's YouTubeClient.WEB_REMIX config.
        ClientConfig(
            label                 = "WEB_REMIX",
            playerUrl             = "https://music.youtube.com/youtubei/v1/player?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-KLET5YdUo&prettyPrint=false",
            clientName            = "WEB_REMIX",
            clientId              = "67",
            clientVersion         = "1.20260501.01.00",
            userAgent             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            useWebAuth            = true,
            useWebPoTokens        = true,
            useSignatureTimestamp = true,
        ),

        // #3 TVHTML5_SIMPLY_EMBEDDED_PLAYER — embedded PS4 UA; bypasses age-restriction without
        // auth.  isEmbedded=true → thirdParty.embedUrl sent in player body (matches Metrolist).
        // useSignatureTimestamp=true matches Metrolist's TVHTML5_SIMPLY_EMBEDDED_PLAYER config.
        ClientConfig(
            label                 = "TVHTML5_SIMPLY_EMBEDDED",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientId              = "85",
            clientVersion         = "2.0",
            userAgent             = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            embedUrlTemplate      = "https://www.youtube.com/watch?v=%VIDEO_ID%",
            supportsAuth          = false,
            useSignatureTimestamp = true,
        ),

        // #4 TVHTML5 — Smart TV UA; n-transform required.  useSignatureTimestamp=true and
        // useWebPoTokens=true match Metrolist's YouTubeClient.TVHTML5 config.
        ClientConfig(
            label                 = "TVHTML5",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "TVHTML5",
            clientId              = "7",
            clientVersion         = "7.20260213.00.00",
            userAgent             = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            supportsAuth          = false,
            useWebPoTokens        = true,
            useSignatureTimestamp = true,
        ),

        // #5 ANDROID_VR (Oculus Quest 3, v1.43.32) — returns plain stream URLs with no
        // signature cipher and no 'n' enforcement.  Comment in Metrolist: "uses non-adaptive
        // bitrate, which fixes audio stuttering with YT Music; does not use AV1."
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
            ),
            supportsAuth  = false,
        ),

        // #6 ANDROID_VR (Oculus Quest 3, v1.61.48) — same as above, newer version.
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
            ),
            supportsAuth  = false,
        ),

        // #7 ANDROID_CREATOR — YouTube Studio Android app (Pixel 9 Pro Fold).
        // Comment from Metrolist: "Cannot play livestreams and lacks HDR, but can play videos with
        // music and labeled 'for children'."  useSignatureTimestamp=true matches Metrolist.
        ClientConfig(
            label                 = "ANDROID_CREATOR",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "ANDROID_CREATOR",
            clientId              = "14",
            clientVersion         = "25.03.101",
            userAgent             = "com.google.android.apps.youtube.creator/25.03.101 (Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
            extraClientFields = mapOf(
                "osName"            to "Android",
                "osVersion"         to "15",
                "deviceMake"        to "Google",
                "deviceModel"       to "Pixel 9 Pro Fold",
                "androidSdkVersion" to "35",
            ),
            supportsAuth          = false,
            useSignatureTimestamp = true,
        ),

        // #8 ANDROID_VR_NO_AUTH — bare ANDROID_VR without any extra context fields.
        // Metrolist uses this as an additional fallback after the extended VR configs.
        // Note: UA uses "Oculus Quest 3" (with "Oculus " prefix) unlike the extended configs.
        // Returns plain CDN URLs — no n-transform required.
        ClientConfig(
            label         = "ANDROID_VR_NO_AUTH",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "ANDROID_VR",
            clientId      = "28",
            clientVersion = "1.61.48",
            userAgent     = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            supportsAuth  = false,
        ),

        // #8 IPADOS — iOS family client with a different version and device model.
        // useSignatureTimestamp is not set (no sig cipher lock), no n-transform required.
        // Metrolist includes this after ANDROID_VR_NO_AUTH in the fallback chain.
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
            ),
            supportsAuth  = false,
        ),

        // #10 MOBILE (ANDROID clientId=3) — the standard YouTube Android client.
        // Requires useSignatureTimestamp=true: without the sts value in the player body YouTube
        // returns cipher-only stream formats.  With it, YouTube returns plain CDN URLs that
        // require no n-transform.  The sts is extracted from the same player JS as the nsig func.
        ClientConfig(
            label                 = "MOBILE",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "ANDROID",
            clientId              = "3",
            clientVersion         = "21.03.38",
            userAgent             = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
            supportsAuth          = false,
            useSignatureTimestamp = true,
        ),

        // #11 IOS — matches Metrolist's YouTubeClient.IOS config exactly.
        ClientConfig(
            label         = "IOS",
            playerUrl     = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName    = "IOS",
            clientId      = "5",
            clientVersion = "21.03.1",
            userAgent     = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            extraClientFields = mapOf(
                "deviceMake"  to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName"      to "iPhone",
                "osVersion"   to "18.2.22C152",
            ),
            supportsAuth = false,
        ),

        // #12 WEB — standard YouTube web client.  Placed at the end of the fallback chain,
        // matching Metrolist's STREAM_FALLBACK_CLIENTS ordering.
        // n-transform required; useWebAuth sends the SAPISIDHASH Authorization header.
        // useSignatureTimestamp=true: without sts YouTube now returns cipher-only stream
        // formats for WEB (same requirement as MOBILE/WEB_CREATOR).
        ClientConfig(
            label                 = "WEB",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "WEB",
            clientId              = "1",
            clientVersion         = "2.20260213.00.00",
            userAgent             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            useWebAuth            = true,
            useSignatureTimestamp = true,
        ),

        // #13 WEB_CREATOR — YouTube Studio web client.  Last resort, matching Metrolist order.
        // useSignatureTimestamp=true and requiresAuth=true match Metrolist's WEB_CREATOR config.
        ClientConfig(
            label                 = "WEB_CREATOR",
            playerUrl             = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName            = "WEB_CREATOR",
            clientId              = "62",
            clientVersion         = "1.20260213.00.00",
            userAgent             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            useWebAuth            = true,
            requiresAuth          = true,
            useSignatureTimestamp = true,
        ),
    )

    private val http = OkHttpClient.Builder()
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
                        put("clientVersion", "1.20260501.01.00")
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
                .header("X-YouTube-Client-Version", "1.20260501.01.00")
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

    suspend fun warmUp() = withContext(Dispatchers.IO) { ensureVisitorData() }

    suspend fun resolveAudioFormatInfo(
        videoId: String,
        preferItag: Int? = null,
        preferHighQuality: Boolean = true,
        sonosSafe: Boolean = false,
    ): AudioFormatInfo? = withContext(Dispatchers.IO) {
        ensureVisitorData()
        val isLoggedIn = ytMusicCookie.isNotBlank()

        for (client in CLIENTS) {
            if (client.requiresAuth && !isLoggedIn) {
                Log.d(TAG, "[${client.label}] skipped — requires auth")
                continue
            }

            // Generate PoToken for web clients that require it (WEB_REMIX, TVHTML5).
            // We keep the full PoTokenResult so we can later append streamingDataPoToken to
            // the CDN URL — this is REQUIRED: without pot= the CDN always returns 403 for
            // WEB_REMIX streams (mirrors Metrolist YTPlayerUtils.kt line 294–302).
            //
            // sessionId is read INSIDE the loop so it benefits from visitorData captured
            // opportunistically from earlier clients' API responses (e.g. ANDROID_TESTSUITE
            // returns "no streams" for music-exclusive tracks but its API response still
            // carries responseContext.visitorData which we cache in fetchPlayerResponse).
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
                        "${afterNDescramble}${sep}pot=${java.net.URLEncoder.encode(poTokenResult.streamingDataPoToken, "UTF-8")}"
                            .also { AppLogger.i(TAG, "[${client.label}] $videoId — pot= appended to stream URL") }
                    } else {
                        afterNDescramble
                    }

                    AppLogger.i(TAG, "[${client.label}] resolved $videoId → itag=${result.info.itag} n-descrambled=$nDescrambled")

                    // Validate with a HEAD request before committing to this URL.
                    //
                    // SKIP for authenticated web clients (WEB, WEB_CREATOR, WEB_REMIX):
                    // These clients generate session-signed CDN URLs.  The URL is perfectly
                    // valid for ExoPlayer (which sends proper User-Agent + Range headers) but
                    // our bare unauthenticated HEAD probe often 403s because the CDN rejects
                    // headless/bot-like requests for premium/login-required content.
                    // Skipping HEAD validation for these clients lets ExoPlayer attempt the
                    // URL directly — if it genuinely fails, ExoPlayer reports the error anyway.
                    //
                    // KEEP for unauthenticated clients (ANDROID_VR, ANDROID_TESTSUITE, etc.)
                    // where the CDN URL should be freely accessible and HEAD validation
                    // filters out bad URLs early without bothering ExoPlayer.
                    val skipHeadValidation = client.useWebAuth || client.requiresAuth
                    if (skipHeadValidation || validateStreamUrl(candidateUrl, client.userAgent)) {
                        if (skipHeadValidation) {
                            AppLogger.i(TAG, "[${client.label}] $videoId — skipping HEAD validation (auth client), passing to ExoPlayer")
                        }
                        return@withContext result.info.copy(url = candidateUrl)
                    } else {
                        AppLogger.w(TAG, "[${client.label}] $videoId — URL failed HEAD validation (403), trying next client")
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
            // the SAME resolveAudioFormatInfo() call without needing a separate fetch
            // (e.g. ANDROID_TESTSUITE returns "no streams" for music-exclusive tracks but
            // its response carries visitorData we can use for WEB_REMIX next).
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
     * Validate a stream URL by making a HEAD request — identical to Metrolist's validateStatus().
     * Returns true if the server responds 2xx, false for 403/404/etc.
     * Skips validation (returns true) if the network call itself fails so we don't block playback
     * on transient connectivity issues.
     *
     * userAgent must match the client that generated the URL.  Without the correct UA the
     * CDN rejects the HEAD probe with 403 even when the URL is perfectly valid for that
     * client's ExoPlayer requests — previously this caused ANDROID_TESTSUITE and Android VR
     * URLs to be discarded before they ever reached the player.
     */
    private fun validateStreamUrl(url: String, userAgent: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", userAgent)
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
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
