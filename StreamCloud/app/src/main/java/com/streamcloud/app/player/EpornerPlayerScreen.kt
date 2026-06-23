@file:Suppress("DEPRECATION")
package com.streamcloud.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plays an eporner video by loading eporner's own embed URL inside a WebView.
 * This avoids the need to extract raw MP4/HLS URLs and lets eporner's native JS
 * player handle adaptive streaming, ads, and DRM automatically.
 *
 * Full-screen toggle is supported via WebChromeClient.onShowCustomView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpornerPlayerScreen(
    embedUrl: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var isFullscreen by remember { mutableStateOf(false) }
    var fsView       by remember { mutableStateOf<View?>(null) }
    var fsCallback   by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    fun exitFullscreen() {
        fsCallback?.onCustomViewHidden()
        fsView = null
        fsCallback = null
        isFullscreen = false
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    BackHandler {
        when {
            isFullscreen -> exitFullscreen()
            else         -> onBack()
        }
    }

    // Build the WebView once; re-build only if embedUrl changes.
    val webView = remember(embedUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled             = true
                domStorageEnabled             = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode          = true
                useWideViewPort               = true
                mixedContentMode              = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Allow inline media playback on Android
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    fsView     = view
                    fsCallback = callback
                    isFullscreen = true
                    (context as? Activity)?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                override fun onHideCustomView() = exitFullscreen()
            }

            webViewClient = object : WebViewClient() {
                // Keep navigation inside the WebView; do not open external browser.
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?,
                ) = false
            }

            loadUrl(embedUrl)
        }
    }

    DisposableEffect(embedUrl) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isFullscreen && fsView != null) {
            // Show the fullscreen video surface provided by the WebChromeClient
            AndroidView(
                factory = { _ ->
                    fsView!!.also { v ->
                        v.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.fillMaxSize()) {

                // ── Thin back bar ────────────────────────────────────────────
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
                        text     = title,
                        color    = Color.White,
                        style    = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── WebView ──────────────────────────────────────────────────
                AndroidView(
                    factory  = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
