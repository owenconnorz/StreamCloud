@file:Suppress("DEPRECATION")

package com.streamcloud.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import org.json.JSONTokener

private data class PornMzNativeStream(
    val url: String,
    val headers: Map<String, String>,
)

private const val PORNMZ_CURRENT_STREAM_JS = """
(function() {
  function activeVideo(doc) {
    return Array.from(doc.querySelectorAll('video'))
      .find(function(video) {
        return !video.paused && video.readyState >= 2;
      });
  }
  var video = activeVideo(document);
  if (!video) {
    var frames = document.querySelectorAll('iframe');
    for (var i = 0; i < frames.length && !video; i++) {
      try {
        var frameDoc = frames[i].contentDocument || frames[i].contentWindow.document;
        if (frameDoc) video = activeVideo(frameDoc);
      } catch (ignored) {}
    }
  }
  if (!video) return '';
  var source = video.querySelector('source');
  return video.currentSrc || (source ? source.src : '') || '';
})()
"""

private const val PORNMZ_PLAY_ONLINE_LINK_JS = """
(function() {
  function findPlayLink(doc) {
    var links = doc.querySelectorAll('a');
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      var label = [
        link.innerText || link.textContent || '',
        link.getAttribute('aria-label') || '',
        link.title || ''
      ].join(' ').replace(/\s+/g, ' ').trim();
      if (!/\bplay\s+online\b/i.test(label) || !link.getClientRects().length) continue;
      try {
        var target = new URL(link.href, doc.location.href);
        var host = target.hostname.toLowerCase();
        if (target.protocol !== 'https:' ||
            (host !== 'pornmz.com' && host !== 'www.pornmz.com') ||
            target.username || target.password ||
            (target.port && target.port !== '443')) continue;
        return target.href;
      } catch (ignored) {}
    }
    return '';
  }
  var target = findPlayLink(document);
  if (!target) {
    var frames = document.querySelectorAll('iframe');
    for (var i = 0; i < frames.length && !target; i++) {
      try {
        var frameDoc = frames[i].contentDocument || frames[i].contentWindow.document;
        if (frameDoc) target = findPlayLink(frameDoc);
      } catch (ignored) {}
    }
  }
  return target || '';
})()
"""

/**
 * Loads the provider's own video page and player. Top-level navigation stays on
 * PornMZ; HTTPS subframes remain available for the provider's embedded player
 * and verification widgets.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PornMzPlayerScreen(
    pageUrl: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val allowedUrl = remember(pageUrl) { isAllowedPornMzPage(pageUrl) }

    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var pageLoading by remember(pageUrl) { mutableStateOf(true) }
    var pageError by remember(pageUrl) { mutableStateOf(false) }
    var nativeStream by remember(pageUrl) { mutableStateOf<PornMzNativeStream?>(null) }
    var currentPageUrl by remember(pageUrl) { mutableStateOf(pageUrl) }
    var playOnlineLinkFollowed by remember(pageUrl) { mutableStateOf(false) }

    fun exitFullscreen() {
        val callback = fullscreenCallback
        fullscreenCallback = null
        callback?.onCustomViewHidden()
        fullscreenView = null
        isFullscreen = false
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    BackHandler {
        if (isFullscreen) exitFullscreen() else onBack()
    }

    val webView = remember(pageUrl, allowedUrl) {
        if (!allowedUrl) {
            null
        } else {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        fullscreenView = view
                        fullscreenCallback = callback
                        isFullscreen = true
                        (context as? Activity)?.requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }

                    override fun onHideCustomView() = exitFullscreen()
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val uri = request?.url ?: return true
                        if (!request.isForMainFrame) {
                            return !uri.scheme.equals("https", ignoreCase = true)
                        }
                        return !isAllowedPornMzPage(uri.toString())
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        pageLoading = true
                        pageError = false
                        url?.takeIf(::isAllowedPornMzPage)?.let { currentPageUrl = it }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoading = false
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            pageLoading = false
                            pageError = true
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            pageLoading = false
                            pageError = true
                        }
                    }
                }
                loadUrl(pageUrl)
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(webView, nativeStream, currentPageUrl, playOnlineLinkFollowed) {
        val playerWebView = webView ?: return@LaunchedEffect
        if (nativeStream != null) return@LaunchedEffect

        while (true) {
            if (!playOnlineLinkFollowed) {
                playerWebView.evaluateJavascript(PORNMZ_PLAY_ONLINE_LINK_JS) { encodedResult ->
                    val targetUrl = runCatching {
                        JSONTokener(encodedResult.orEmpty()).nextValue() as? String
                    }.getOrNull()?.takeIf(::isAllowedPornMzPage)
                        ?: return@evaluateJavascript
                    playOnlineLinkFollowed = true
                    if (targetUrl != playerWebView.url) {
                        playerWebView.loadUrl(targetUrl)
                    }
                }
            }
            playerWebView.evaluateJavascript(PORNMZ_CURRENT_STREAM_JS) { encodedResult ->
                val streamUrl = runCatching {
                    JSONTokener(encodedResult.orEmpty()).nextValue() as? String
                }.getOrNull()?.takeIf(::isNativePlayablePornMzStream)
                    ?: return@evaluateJavascript
                val streamUri = Uri.parse(streamUrl)
                val headers = buildMap {
                    put(
                        "User-Agent",
                        WebSettings.getDefaultUserAgent(context)
                            .replace("; wv", "")
                            .replace(" Version/4.0", ""),
                    )
                    put("Referer", currentPageUrl)
                    if (streamUri.host?.equals("pornmz.com", ignoreCase = true) == true ||
                        streamUri.host?.equals("www.pornmz.com", ignoreCase = true) == true
                    ) {
                        android.webkit.CookieManager.getInstance()
                            .getCookie(streamUrl)
                            ?.takeIf(String::isNotBlank)
                            ?.let { put("Cookie", it) }
                    }
                }
                nativeStream = PornMzNativeStream(streamUrl, headers)
                playerWebView.onPause()
            }
            delay(1_000)
        }
    }

    nativeStream?.let { stream ->
        NativePlayerScreen(
            streamUrl = stream.url,
            title = title,
            headers = stream.headers,
            onBack = onBack,
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            !allowedUrl -> {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("This PornMZ link is not allowed.", color = Color.White)
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                }
            }
            isFullscreen && fullscreenView != null -> {
                AndroidView(
                    factory = {
                        fullscreenView!!.also { view ->
                            view.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D0D0D))
                            .statusBarsPadding()
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                        webView?.let { playerWebView ->
                            AndroidView(
                                factory = { playerWebView },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (pageLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = Color.White,
                            )
                        }
                        if (pageError) {
                            Text(
                                "PornMZ could not load this page. Check your connection or complete any verification shown by the site.",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isAllowedPornMzPage(rawUrl: String): Boolean {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        host in setOf("pornmz.com", "www.pornmz.com") &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443)
}

private fun isNativePlayablePornMzStream(rawUrl: String): Boolean {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        !rawUrl.startsWith("blob:", ignoreCase = true)
}
