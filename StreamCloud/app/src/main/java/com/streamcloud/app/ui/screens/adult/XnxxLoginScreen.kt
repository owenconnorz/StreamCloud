@file:Suppress("DEPRECATION")

package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.streamcloud.app.ui.icons.AppIcons as Icons
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private const val XNXX_ACCOUNT_URL = "https://www.xnxx.com/account/"

private val XNXX_SIGNED_IN_CHECK = """
    (function() {
      var bodyText = document.body ? document.body.innerText : '';
      var logoutControl = document.querySelector(
        'a[href*="logout" i], a[href*="signout" i], ' +
        'button[aria-label*="log out" i], button[aria-label*="sign out" i], ' +
        '[data-testid*="logout" i], [data-role*="logout" i]'
      );
      return !!logoutControl || /\b(log\s*out|logout|sign\s*out)\b/i.test(bodyText);
    })();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XnxxLoginScreen(
    onSessionSaved: () -> Unit,
    onBack: () -> Unit,
) {
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var sessionDetected by remember { mutableStateOf(false) }
    var canReturn by remember { mutableStateOf(false) }
    var manuallyConfirmed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    val screenDisposed = remember { AtomicBoolean(false) }

    fun destroyWebView(view: WebView?) {
        if (view == null) return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.stopLoading() }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.destroy() }
        if (webView === view) webView = null
    }

    fun disposeScreen() {
        if (!screenDisposed.compareAndSet(false, true)) return
        CookieManager.getInstance().flush()
        destroyWebView(webView)
    }

    fun finishOrGoBack() {
        if (screenDisposed.get()) return
        CookieManager.getInstance().flush()
        val shouldSaveSession = sessionDetected || manuallyConfirmed
        disposeScreen()
        if (shouldSaveSession) onSessionSaved() else onBack()
    }

    BackHandler(onBack = ::finishOrGoBack)
    DisposableEffect(Unit) {
        onDispose(::disposeScreen)
    }

    LaunchedEffect(webViewGeneration, webView, sessionDetected) {
        while (!sessionDetected && !screenDisposed.get()) {
            val activeWebView = webView
            val uri = runCatching { Uri.parse(activeWebView?.url.orEmpty()) }.getOrNull()
            if (activeWebView != null && isXnxxLoginHost(uri?.host)) {
                activeWebView.evaluateJavascript(XNXX_SIGNED_IN_CHECK) { result ->
                    if (!screenDisposed.get() && result == "true") {
                        sessionDetected = true
                        canReturn = true
                    }
                }
            }
            delay(1_000L)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .statusBarsPadding()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::finishOrGoBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (sessionDetected) "Signed in to XNXX" else "Sign in to XNXX",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        sessionDetected -> "Your official XNXX session is ready"
                        canReturn -> "After sign-in or verification, tap Done"
                        else -> "Complete any XNXX checks on this official page"
                    },
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (sessionDetected || canReturn) {
                TextButton(
                    onClick = {
                        manuallyConfirmed = !sessionDetected
                        finishOrGoBack()
                    },
                    enabled = !pageLoading,
                ) {
                    Text("Done", color = Color(0xFF4F8DFF))
                }
            }
        }

        if (pageLoading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = Color(0xFF4F8DFF),
            )
        }

        Box(Modifier.fillMaxSize()) {
            key(webViewGeneration) {
                AndroidView(
                    factory = { context ->
                        WebView(context).also { view ->
                            webView = view
                            view.isFocusable = true
                            view.isFocusableInTouchMode = true
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.useWideViewPort = true
                            view.settings.loadWithOverviewMode = true
                            view.settings.userAgentString =
                                WebSettings.getDefaultUserAgent(context)
                                    .replace("; wv", "")
                                    .replace(" Version/4.0", "")
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                            view.webChromeClient = WebChromeClient()
                            view.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    if (screenDisposed.get()) return true
                                    val uri = request?.url ?: return true
                                    if (uri.scheme.equals("about", ignoreCase = true)) return false
                                    return !isAllowedXnxxLoginNavigation(
                                        scheme = uri.scheme,
                                        host = uri.host,
                                        isMainFrame = request.isForMainFrame,
                                    )
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    if (screenDisposed.get()) return
                                    pageLoading = true
                                    pageError = null
                                    val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                    if (isXnxxLoginHost(uri?.host)) {
                                        canReturn = !isXnxxAccountPath(uri?.path)
                                        if (isXnxxAccountPath(uri?.path)) sessionDetected = false
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (screenDisposed.get()) return
                                    if (request?.isForMainFrame == true) {
                                        pageError = error?.description?.toString()
                                            ?: "XNXX could not load the sign-in page."
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    response: android.webkit.WebResourceResponse?,
                                ) {
                                    super.onReceivedHttpError(view, request, response)
                                    if (screenDisposed.get()) return
                                    if (request?.isForMainFrame == true) {
                                        pageError = when (response?.statusCode) {
                                            403 -> "XNXX requires official verification. Complete it on this page."
                                            429 -> "XNXX is limiting requests. Wait a moment and retry."
                                            else -> "XNXX returned HTTP ${response?.statusCode ?: "an error"}."
                                        }
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    destroyWebView(view)
                                    if (!screenDisposed.get()) {
                                        pageLoading = false
                                        pageError = "XNXX’s sign-in page stopped. Tap Retry to reopen it."
                                    }
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (screenDisposed.get() || view == null) return
                                    pageLoading = false
                                    CookieManager.getInstance().flush()
                                    val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                        ?: return
                                    if (!isXnxxLoginHost(uri.host)) return
                                    if (!isXnxxAccountPath(uri.path)) canReturn = true
                                    view.evaluateJavascript(XNXX_SIGNED_IN_CHECK) { result ->
                                        if (!screenDisposed.get() && result == "true") {
                                            sessionDetected = true
                                            canReturn = true
                                        }
                                    }
                                }
                            }
                            view.loadUrl(XNXX_ACCOUNT_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            pageError?.let { message ->
                Surface(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = Color(0xFF202A3A),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            Modifier.weight(1f),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = {
                            pageError = null
                            pageLoading = true
                            val activeWebView = webView
                            if (activeWebView == null) webViewGeneration++ else activeWebView.reload()
                        }) {
                            Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

internal fun isXnxxLoginHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trimEnd('.') ?: return false
    return normalized == "xnxx.com" || normalized.endsWith(".xnxx.com")
}

internal fun isXnxxAccountPath(path: String?): Boolean =
    path?.trimEnd('/') == "/account"

internal fun isAllowedXnxxLoginNavigation(
    scheme: String?,
    host: String?,
    isMainFrame: Boolean,
): Boolean {
    if (!scheme.equals("https", ignoreCase = true)) return false
    return !isMainFrame || isXnxxLoginHost(host)
}
