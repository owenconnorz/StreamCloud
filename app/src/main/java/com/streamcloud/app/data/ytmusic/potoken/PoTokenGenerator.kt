package com.streamcloud.app.data.ytmusic.potoken

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false

    private val lock = Mutex()
    private var sessionId: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    fun getWebClientPoToken(context: android.content.Context, videoId: String, sessionId: String): PoTokenResult? {
        Log.d(TAG, "getWebClientPoToken: videoId=$videoId, webViewOk=$webViewSupported, badImpl=$webViewBadImpl")
        if (!webViewSupported || webViewBadImpl) return null
        return try {
            runBlocking { getWebClientPoTokenSuspend(context, videoId, sessionId, forceRecreate = false) }
        } catch (e: BadWebViewException) {
            Log.e(TAG, "Broken WebView — disabling PoToken", e)
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            Log.e(TAG, "PoToken failed: ${e.message}")
            null
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
                Log.d(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                sessionId = currentSessionId
                withContext(Dispatchers.Main) { generator?.close() }
                generator = PoTokenWebView.getNewPoTokenGenerator(context)
                streamingPot = generator!!.generatePoToken(currentSessionId)
                Log.d(TAG, "Streaming poToken generated")
            }
            Triple(generator!!, streamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            gen.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (recreated) throw t
            Log.e(TAG, "generatePoToken failed, retrying with fresh generator", t)
            return getWebClientPoTokenSuspend(context, videoId, currentSessionId, forceRecreate = true)
        }

        return PoTokenResult(playerPot, sPot)
    }
}
