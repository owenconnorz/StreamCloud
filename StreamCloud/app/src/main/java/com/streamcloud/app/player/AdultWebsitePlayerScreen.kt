package com.streamcloud.app.player

import android.graphics.Color as AndroidColor
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamcloud.app.data.api.AdultSource
import com.streamcloud.app.data.api.AdultWebsiteRepository
import com.streamcloud.app.data.network.BrowserHeaders

@Composable
fun AdultWebsiteBrowserScreen(
    sourceKey: String,
    pageUrl: String,
    title: String,
    onBack: () -> Unit,
) {
    val source = remember(sourceKey) {
        when (sourceKey.lowercase()) {
            "porn4days" -> AdultSource.Porn4Days
            "pornhd3x" -> AdultSource.PornHd3x
            else -> null
        }
    }
    val safePageUrl = source?.let { AdultWebsiteRepository.normalizePageUrl(it, pageUrl) }
    val webViewState = remember(safePageUrl) { mutableStateOf<WebView?>(null) }
    var loading by remember(safePageUrl) { mutableStateOf(true) }
    var pageError by remember(safePageUrl) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val navigateBack = {
        val webView = webViewState.value
        if (webView?.canGoBack() == true) webView.goBack() else onBack()
    }
    BackHandler(enabled = true) { navigateBack() }

    DisposableEffect(webViewState) {
        onDispose {
            webViewState.value?.let { webView ->
                webView.stopLoading()
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.destroy()
            }
            webViewState.value = null
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
                .background(Color(0xFF151515))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = navigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = {
                    pageError = null
                    webViewState.value?.reload()
                },
                enabled = safePageUrl != null,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reload provider page",
                    tint = Color.White,
                )
            }
        }

        if (safePageUrl == null || source == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "This provider page is not allowed.",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            if (loading) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        WebView(context).apply {
                            setBackgroundColor(AndroidColor.BLACK)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.supportMultipleWindows = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.userAgentString = BrowserHeaders.USER_AGENT
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loading = newProgress < 100
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val navigation = request ?: return true
                                    val url = navigation.url.toString()
                                    return if (navigation.isForMainFrame) {
                                        !AdultWebsiteRepository.isAllowedMainFrameUrl(source, url)
                                    } else {
                                        !navigation.url.scheme.equals("https", ignoreCase = true)
                                    }
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    loading = true
                                    pageError = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        pageError = "The provider page could not be loaded. Check your connection and retry."
                                    }
                                }
                            }
                            webViewState.value = this
                            loadUrl(safePageUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url.isNullOrBlank()) webView.loadUrl(safePageUrl)
                    },
                )
                pageError?.let { message ->
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(message, color = Color.White)
                        IconButton(onClick = {
                            pageError = null
                            webViewState.value?.reload()
                        }) {
                            Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                        }
                    }
                }
                if (loading && webViewState.value == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
            }
        }
    }
}
