package com.streamcloud.app.ads

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun AdvertisingBanner(
    placement: AdPlacement,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview by AdPreferences.previewEnabled(context)
    val canLoad = LiveAdPolicy.mayRequestNetwork(LiveAdPolicy.current)

    if (!preview && !canLoad) return
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (preview) {
            PreviewBanner(Modifier)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Advertisement", style = MaterialTheme.typography.labelSmall)
                PreparedAdWebView(placement, Modifier)
            }
        }
    }
}

@Composable
private fun PreviewBanner(modifier: Modifier) {
    Box(
        modifier = modifier
            .width(ExoClickTag.WIDTH_DP.dp)
            .height(ExoClickTag.HEIGHT_DP.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Advertisement", style = MaterialTheme.typography.labelMedium)
            Text(
                "Preview — no ad requested",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreparedAdWebView(
    @Suppress("UNUSED_PARAMETER") placement: AdPlacement,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier
            .width(ExoClickTag.WIDTH_DP.dp)
            .height(ExoClickTag.HEIGHT_DP.dp),
        factory = {
            hardenedWebView(context).also { view ->
                webView = view
                // This line is unreachable with current fail-closed readiness.
                view.loadDataWithBaseURL(null, buildExoClickHtml(), "text/html", "UTF-8", null)
            }
        },
    )
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    webView?.stopLoading()
                    webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.removeAllViews()
            webView?.destroy()
            webView = null
        }
    }
}

private fun hardenedWebView(context: Context) = WebView(context).apply {
    setBackgroundColor(Color.TRANSPARENT)
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setGeolocationEnabled(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    setDownloadListener { _, _, _, _, _ -> }
    webChromeClient = object : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, false, false)
        }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            if (request.hasGesture() && isSafeExternalClick(url)) {
                openExternalHttps(context, url)
                return true
            }
            return request.isForMainFrame ||
                !(isSafeExternalClick(url) || url == "about:blank")
        }
    }
}

internal fun openExternalHttps(context: Context, url: String): Boolean {
    if (!isSafeExternalClick(url)) return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    }.getOrDefault(false)
}