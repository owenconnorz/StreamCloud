package com.streamcloud.app.data.ytmusic.potoken

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PoTokenWebView private constructor(
    context: Context,
    private val continuation: Continuation<PoTokenWebView>,
) {
    private val webView = WebView(context)
    private val scope = MainScope()
    private val poTokenContinuations = ConcurrentHashMap<String, Continuation<String>>()
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        onInitializationErrorCloseAndCancel(t)
    }
    private lateinit var expirationInstant: Instant

    init {
        val s = webView.settings
        @Suppress("SetJavaScriptEnabled")
        s.javaScriptEnabled = true
        s.userAgentString = USER_AGENT
        s.blockNetworkLoads = true

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val msg = m.message()
                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "JS: $msg")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $msg")
                    else -> Log.d(TAG, "JS: $msg")
                }
                if (msg.contains("Uncaught")) {
                    val fmt = "\"$msg\", source: ${m.sourceId()} (${m.lineNumber()})"
                    val ex = BadWebViewException(fmt)
                    Log.e(TAG, "Broken WebView: $fmt")
                    onInitializationErrorCloseAndCancel(ex)
                    popAllPoTokenContinuations().forEach { (_, c) -> c.resumeWithException(ex) }
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun loadHtmlAndObtainBotguard() {
        scope.launch(exceptionHandler) {
            val html = withContext(Dispatchers.IO) {
                webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
            }
            val data = html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>")
            webView.loadDataWithBaseURL("https://www.youtube.com", data, "text/html", "utf-8", null)
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Log.d(TAG, "downloadAndRunBotguard()")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { responseBody ->
            val parsed = parseChallengeData(responseBody)
            webView.evaluateJavascript(
                """try {
                    data = $parsed
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""", null
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, "JS init error: $error")
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        Log.d(TAG, "onRunBotguardResult")
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { responseBody ->
            try {
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds)
                    .minus(10, ChronoUnit.MINUTES)
                webView.evaluateJavascript(
                    """try {
                        this.integrityToken = $integrityToken
                        createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                            $JS_INTERFACE.onMinterCreated()
                        }).catch(function(error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + (error.stack || ''))
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""", null
                )
            } catch (e: Exception) {
                Log.e(TAG, "parseIntegrityTokenData failed: ${e.message}")
                onInitializationErrorCloseAndCancel(PoTokenException("parseIntegrityTokenData: ${e.message}"))
            }
        }
    }

    @JavascriptInterface
    fun onMinterCreated() {
        Log.d(TAG, "PoToken minter ready")
        continuation.resume(this)
    }

    suspend fun generatePoToken(identifier: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            addPoTokenEmitter(identifier, cont)
            webView.evaluateJavascript(
                """try {
                    identifier = "$identifier"
                    u8Identifier = ${stringToU8(identifier)}
                    obtainPoToken(u8Identifier).then(function(poTokenU8) {
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8.join(","))
                    }).catch(function(error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + (error.stack || ''))
                    })
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""", null
            )
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.e(TAG, "obtainPoToken error: $error")
        popPoTokenContinuation(identifier)?.resumeWithException(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try { u8ToBase64(poTokenU8) } catch (t: Throwable) {
            popPoTokenContinuation(identifier)?.resumeWithException(t); return
        }
        popPoTokenContinuation(identifier)?.resume(poToken)
    }

    val isExpired: Boolean get() = Instant.now().isAfter(expirationInstant)

    private fun addPoTokenEmitter(id: String, c: Continuation<String>) { poTokenContinuations[id] = c }
    private fun popPoTokenContinuation(id: String) = poTokenContinuations.remove(id)
    private fun popAllPoTokenContinuations(): Map<String, Continuation<String>> {
        val r = poTokenContinuations.toMap(); poTokenContinuations.clear(); return r
    }

    private fun makeBotguardServiceRequest(url: String, data: String, onResponse: (String) -> Unit) {
        scope.launch(exceptionHandler) {
            val req = okhttp3.Request.Builder()
                .post(data.toRequestBody())
                .headers(mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json+protobuf",
                    "x-goog-api-key" to GOOGLE_API_KEY,
                    "x-user-agent" to "grpc-web-javascript/0.1",
                ).toHeaders())
                .url(url)
                .build()
            val resp = withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
            if (resp.code != 200) {
                onInitializationErrorCloseAndCancel(PoTokenException("BotGuard HTTP ${resp.code}"))
            } else {
                val body = withContext(Dispatchers.IO) { resp.body!!.string() }
                onResponse(body)
            }
        }
    }

    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        close()
        continuation.resumeWithException(error)
    }

    private fun buildExceptionForJsError(error: String): Throwable =
        if (error.contains("Uncaught")) BadWebViewException(error) else PoTokenException(error)

    @MainThread
    fun close() {
        scope.cancel()
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        private val httpClient = OkHttpClient()

        suspend fun getNewPoTokenGenerator(context: Context): PoTokenWebView =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val wv = PoTokenWebView(context, cont)
                    wv.loadHtmlAndObtainBotguard()
                }
            }
    }
}
