@file:Suppress("DEPRECATION")

package com.streamcloud.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamcloud.app.data.api.XHamsterRepository
import kotlinx.coroutines.delay
import org.json.JSONTokener

private const val XHAMSTER_CURRENT_STREAM_JS = """
    (function() {
      var videos = Array.from(document.querySelectorAll('video'))
        .filter(function(video) { return !video.paused && video.readyState >= 2; });
      videos.sort(function(a, b) {
        return (b.videoWidth * b.videoHeight) - (a.videoWidth * a.videoHeight);
      });
      var video = videos[0];
      var source = video ? (video.currentSrc || video.src || '') : '';
      return /^https:\/\//i.test(source) ? source : '';
    })();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XHamsterPlayerScreen(
    videoId: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pageUrl = remember(videoId) {
        runCatching { XHamsterRepository.videoPageUrl(videoId) }.getOrNull()
    }
    var progress by remember(videoId) { mutableIntStateOf(0) }
    var loadError by remember(videoId) {
        mutableStateOf(if (pageUrl == null) "This XHamster video link is invalid." else null)
    }
    var nativeStream by remember(videoId) { mutableStateOf<XHamsterNativeStream?>(null) }
    var isFullscreen by remember(videoId) { mutableStateOf(false) }
    var fullscreenView by remember(videoId) { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember(videoId) {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
        isFullscreen = false
        (context as? Activity)?.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    val webView = remember(videoId, pageUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                userAgentString = WebSettings.getDefaultUserAgent(context)
                    .replace("; wv", "")
                    .replace(" Version/4.0", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }

                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?,
                ) {
                    if (view == null) return
                    fullscreenView = view
                    fullscreenCallback = callback
                    isFullscreen = true
                    (context as? Activity)?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url ?: return true
                    return request.isForMainFrame && !isTrustedXHamsterPage(target)
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    loadError = null
                    progress = 0
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loadError = "XHamster could not load this page. Check your connection and retry."
                    }
                }
            }
            pageUrl?.let(::loadUrl)
        }
    }

    BackHandler(enabled = nativeStream == null) {
        when {
            isFullscreen -> exitFullscreen()
            webView.canGoBack() -> webView.goBack()
            else -> onBack()
        }
    }

    LaunchedEffect(webView, pageUrl, nativeStream) {
        if (pageUrl == null || nativeStream != null) return@LaunchedEffect
        while (nativeStream == null) {
            if (progress >= 95) {
                webView.evaluateJavascript(XHAMSTER_CURRENT_STREAM_JS) { encodedResult ->
                    if (nativeStream != null) return@evaluateJavascript
                    val streamUrl = runCatching {
                        JSONTokener(encodedResult.orEmpty()).nextValue() as? String
                    }.getOrNull()?.takeIf(String::isNotBlank) ?: return@evaluateJavascript
                    if (!isNativePlayableXHamsterStream(streamUrl)) return@evaluateJavascript

                    val headers = mapOf(
                        "User-Agent" to WebSettings.getDefaultUserAgent(context)
                            .replace("; wv", "")
                            .replace(" Version/4.0", ""),
                        "Referer" to pageUrl.orEmpty(),
                    )
                    webView.onPause()
                    nativeStream = XHamsterNativeStream(streamUrl, headers)
                }
            }
            delay(1_000)
        }
    }
    LaunchedEffect(nativeStream) {
        if (nativeStream == null) webView.onResume() else webView.onPause()
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val activeNativeStream = nativeStream
    if (activeNativeStream != null) {
        NativePlayerScreen(
            streamUrl = activeNativeStream.url,
            title = title,
            headers = activeNativeStream.headers,
            onBack = { nativeStream = null },
        )
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (isFullscreen && fullscreenView != null) {
            AndroidView(
                factory = { _ ->
                    fullscreenView!!.also { view ->
                        view.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101010))
                        .statusBarsPadding()
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (webView.canGoBack()) webView.goBack() else onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        loadError = null
                        webView.reload()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reload XHamster",
                            tint = Color.White,
                        )
                    }
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                loadError?.let { message ->
                    Text(
                        text = message,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF242424))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun isTrustedXHamsterPage(uri: Uri): Boolean {
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "xhamster.com" || host.endsWith(".xhamster.com"))
}

internal fun isNativePlayableXHamsterStream(rawUrl: String): Boolean {
    val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
    val path = uri.path.orEmpty().lowercase()
    return listOf(".m3u8", ".mp4", ".m4v", ".webm", ".mpd")
        .any(path::endsWith)
}

private data class XHamsterNativeStream(
    val url: String,
    val headers: Map<String, String>,
)
