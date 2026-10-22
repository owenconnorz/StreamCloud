@file:Suppress("DEPRECATION")

package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.streamcloud.app.ui.icons.AppIcons as Icons
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
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XHamsterLoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var canFinish by remember { mutableStateOf(false) }
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
        destroyWebView(webView)
    }

    fun finishOrGoBack() {
        if (screenDisposed.get()) return
        CookieManager.getInstance().flush()
        val signedIn = canFinish
        disposeScreen()
        if (signedIn) onLoginSuccess() else onBack()
    }

    BackHandler(onBack = ::finishOrGoBack)
    DisposableEffect(Unit) {
        onDispose(::disposeScreen)
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
                    if (canFinish) "Signed in to XHamster" else "Sign in to XHamster",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (canFinish) "Press Back or Done to return"
                    else "Use XHamster’s official page to sign in",
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (canFinish) {
                TextButton(onClick = ::finishOrGoBack, enabled = !pageLoading) {
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
                            view.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    if (screenDisposed.get()) return true
                                    val uri = request?.url ?: return true
                                    if (uri.scheme.equals("about", ignoreCase = true)) return false
                                    return !isAllowedXHamsterLoginNavigation(
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
                                    if (uri?.path?.trimEnd('/') == "/login") {
                                        canFinish = false
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
                                            ?: "XHamster could not load the sign-in page."
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
                                            403 -> "XHamster blocked this page. Follow its verification steps and retry."
                                            429 -> "XHamster is limiting requests. Wait a moment and retry."
                                            else -> "XHamster returned HTTP ${response?.statusCode ?: "an error"}."
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
                                        pageError = "XHamster’s sign-in page stopped. Tap Retry to reopen it."
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
                                    if (!isXHamsterLoginHost(uri.host) ||
                                        uri.path?.trimEnd('/') == "/login"
                                    ) {
                                        return
                                    }
                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                          var text = document.body ? document.body.innerText : '';
                                          return /(^|\n)\s*(log\s*out|logout|sign\s*out)\b/i.test(text);
                                        })();
                                        """.trimIndent(),
                                    ) { result ->
                                        if (!screenDisposed.get()) canFinish = result == "true"
                                    }
                                }
                            }
                            view.loadUrl("https://xhamster.com/login")
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
                            if (activeWebView == null) {
                                webViewGeneration++
                            } else {
                                activeWebView.reload()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

internal fun isXHamsterLoginHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trimEnd('.') ?: return false
    return normalized == "xhamster.com" || normalized.endsWith(".xhamster.com")
}

internal fun isAllowedXHamsterLoginNavigation(
    scheme: String?,
    host: String?,
    isMainFrame: Boolean,
): Boolean {
    if (!scheme.equals("https", ignoreCase = true)) return false
    return !isMainFrame || isXHamsterLoginHost(host)
}
