package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val PORNHUB_SHORTIES_URL = "https://www.pornhub.com/shorties"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PornhubShortiesScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var initialRouteConfirmed by remember { mutableStateOf(false) }
    val webView = remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val current = webView.value
        if (current?.canGoBack() == true) current.goBack() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.value?.let {
                runCatching { it.stopLoading() }
                runCatching { it.onPause() }
                runCatching { it.webChromeClient = null }
                runCatching { it.webViewClient = null }
                runCatching { it.removeAllViews() }
                runCatching { it.destroy() }
            }
            webView.value = null
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request ?: return true
                            return !isAllowedPornhubShortiesNavigation(target.url.scheme, target.url.host, target.isForMainFrame)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            loading = false
                            if (!initialRouteConfirmed) {
                                if (isPornhubShortiesPageUrl(url.orEmpty())) initialRouteConfirmed = true
                                else pageError = "Pornhub redirected away from Shorties. Complete any required verification, then retry."
                            }
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                loading = false
                                pageError = "Pornhub Shorties could not load. Check your connection and retry."
                            }
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            super.onReceivedHttpError(view, request, response)
                            if (request?.isForMainFrame == true) {
                                loading = false
                                pageError = "Pornhub Shorties returned an error. Retry in a moment."
                            }
                        }
                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            loading = false
                            pageError = "Shorties stopped unexpectedly. Close and reopen the feed to retry."
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, progress: Int) {
                            loading = progress < 100
                        }
                    }
                    webView.value = this
                    loadUrl(PORNHUB_SHORTIES_URL)
                }
            },
            update = { webView.value = it },
        )
        if (pageError != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Shorties unavailable", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(pageError.orEmpty(), color = Color.White.copy(alpha = 0.78f))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        pageError = null
                        loading = true
                        webView.value?.loadUrl(PORNHUB_SHORTIES_URL)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9000), contentColor = Color.Black),
                ) { Text("Retry") }
            }
        } else if (loading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF9000))
            }
        }
    }
}

internal fun isAllowedPornhubShortiesNavigation(scheme: String?, host: String?, isMainFrame: Boolean): Boolean {
    if (scheme.equals("about", ignoreCase = true)) return !isMainFrame
    if (!scheme.equals("https", ignoreCase = true)) return false
    if (!isMainFrame) return true
    val domain = host?.trimEnd('.')?.lowercase() ?: return false
    return domain == "pornhub.com" || domain.endsWith(".pornhub.com")
}

internal fun isPornhubShortiesPageUrl(url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!isAllowedPornhubShortiesNavigation(parsed.scheme, parsed.host, true)) return false
    val path = parsed.path.orEmpty().trimEnd('/')
    return path.equals("/shorties", true) || path.startsWith("/shorties/", true)
}
