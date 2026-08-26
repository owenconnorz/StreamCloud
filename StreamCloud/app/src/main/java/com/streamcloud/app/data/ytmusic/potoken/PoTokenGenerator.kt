package com.streamcloud.app.data.ytmusic.potoken

import android.util.Log
import android.webkit.CookieManager
import com.streamcloud.app.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy {
        val ok = runCatching { CookieManager.getInstance() }.isSuccess
        if (!ok) AppLogger.e(TAG, "WebView not supported on this device — PoToken disabled")
        ok
    }

    // Time-based bad-impl guard: after a BadWebViewException we back off for BAD_IMPL_BACKOFF_MS
    // before retrying.  A permanent flag caused PoToken to be disabled for the rest of the
    // session after a single transient failure (e.g. JS engine hiccup on first load).
    private var webViewBadImplSince: Long = 0L
    private val BAD_IMPL_BACKOFF_MS = 120_000L  // 2 minutes

    private val lock = Mutex()
    private var sessionId: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    fun getWebClientPoToken(context: android.content.Context, videoId: String, sessionId: String): PoTokenResult? {
        if (!webViewSupported) return null

        val now = System.currentTimeMillis()
        val inBackoff = webViewBadImplSince > 0 && now - webViewBadImplSince < BAD_IMPL_BACKOFF_MS
        if (inBackoff) {
            val remaining = (BAD_IMPL_BACKOFF_MS - (now - webViewBadImplSince)) / 1000
            AppLogger.w(TAG, "PoToken in back-off after WebView error (${remaining}s remaining) — skipping")
            return null
        }

        AppLogger.i(TAG, "Generating PoToken for $videoId")
        return try {
            runBlocking { getWebClientPoTokenSuspend(context, videoId, sessionId, forceRecreate = false) }
        } catch (e: BadWebViewException) {
            AppLogger.e(TAG, "Broken WebView — PoToken backed off for ${BAD_IMPL_BACKOFF_MS / 1000}s: ${e.message}")
            webViewBadImplSince = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "PoToken failed: ${e.message}")
            null
        }
    }

    /**
     * Create and warm the reusable BotGuard/WebView session before a user starts playback.
     *
     * The player token remains video-specific, but initializing the shared generator and
     * streaming token here avoids making a first song wait for WebView startup.
     */
    suspend fun warmUp(
        context: android.content.Context,
        sessionId: String,
        warmUpVideoId: String,
    ) {
        if (sessionId.isBlank() || warmUpVideoId.isBlank() || !webViewSupported) return

        val now = System.currentTimeMillis()
        if (webViewBadImplSince > 0 && now - webViewBadImplSince < BAD_IMPL_BACKOFF_MS) return

        try {
            getWebClientPoTokenSuspend(
                context = context,
                videoId = warmUpVideoId,
                currentSessionId = sessionId,
                forceRecreate = false,
            )
            AppLogger.i(TAG, "PoToken generator warmed for playback")
        } catch (e: BadWebViewException) {
            webViewBadImplSince = System.currentTimeMillis()
            AppLogger.w(TAG, "PoToken warm-up skipped after WebView error: ${e.message}")
        } catch (e: Exception) {
            // Playback still has its normal anonymous-client fallbacks when background warm-up
            // cannot complete (for example on devices without a usable WebView).
            AppLogger.w(TAG, "PoToken warm-up failed: ${e.message}")
        }
    }

    private suspend fun getWebClientPoTokenSuspend(
        context: android.content.Context,
        videoId: String,
        currentSessionId: String,
        forceRecreate: Boolean,
    ): PoTokenResult {
        val (gen, sPot, recreated) = lock.withLock {
            val shouldRecreate = forceRecreate
                || generator == null
                || generator!!.isExpired
                || sessionId != currentSessionId

            if (shouldRecreate) {
                AppLogger.i(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                sessionId = currentSessionId
                withContext(Dispatchers.Main) { generator?.close() }
                generator = PoTokenWebView.getNewPoTokenGenerator(context)
                streamingPot = generator!!.generatePoToken(currentSessionId)
                AppLogger.i(TAG, "Streaming PoToken ready")
            }
            Triple(generator!!, streamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            gen.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (recreated) throw t
            AppLogger.w(TAG, "generatePoToken failed, retrying with fresh generator: ${t.message}")
            return getWebClientPoTokenSuspend(context, videoId, currentSessionId, forceRecreate = true)
        }

        AppLogger.i(TAG, "PoToken generated ok for $videoId")
        return PoTokenResult(playerPot, sPot)
    }
}
