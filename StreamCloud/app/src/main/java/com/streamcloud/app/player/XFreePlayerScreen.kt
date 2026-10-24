@file:Suppress("DEPRECATION")

package com.streamcloud.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamcloud.app.data.api.XFreeRepository
import com.streamcloud.app.data.api.configureXFreeWebViewSession
import com.streamcloud.app.ui.icons.AppIcons as Icons
import com.streamcloud.app.ui.screens.adult.isAllowedXFreeLoginNavigation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses XFree's official video page and player instead of extracting media URLs.
 * Allows official account/verification handoffs without replacing the video's
 * opener or fabricating a verified session.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XFreePlayerScreen(
    videoId: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val videoUrl = remember(videoId) { XFreeRepository.videoPageUrl(videoId) }
    val browserContainer = remember(videoUrl) { FrameLayout(context) }
    val disposed = remember(videoUrl) { AtomicBoolean(false) }
    var popupWebView by remember(videoUrl) { mutableStateOf<WebView?>(null) }
    var navigationError by remember(videoUrl) { mutableStateOf<String?>(null) }

    fun closePopup() {
        val popup = popupWebView ?: return
        popupWebView = null
        CookieManager.getInstance().flush()
        (popup.parent as? ViewGroup)?.removeView(popup)
        popup.stopLoading()
        popup.webChromeClient = null
        popup.destroy()
    }

    fun navigationClient() = object : WebViewClient() {
        fun block(url: Uri?, mainFrame: Boolean): Boolean {
            if (disposed.get()) return true
            val allowed = url != null && isAllowedXFreeLoginNavigation(
                url.scheme, url.host, mainFrame,
            )
            if (!allowed && mainFrame) {
                navigationError = "This destination is not supported by the in-app player."
            }
            return !allowed
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
            request == null || block(request.url, request.isForMainFrame)

        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            block(url?.let(Uri::parse), true)

        override fun onPageFinished(view: WebView?, url: String?) {
            CookieManager.getInstance().flush()
        }
    }

    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
        isFullscreen = false
        (context as? Activity)?.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun handleBack() {
        when {
            isFullscreen -> exitFullscreen()
            popupWebView != null -> closePopup()
            else -> onBack()
        }
    }
    BackHandler(onBack = ::handleBack)

    val webView = remember(videoUrl) {
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
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }
            configureXFreeWebViewSession(this, context)

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    // Keep the existing opener and never replace an active verification window.
                    if (disposed.get() || popupWebView != null) return false
                    val message = resultMsg ?: return false
                    val transport = message.obj as? WebView.WebViewTransport ?: return false
                    val popup = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(true)
                        configureXFreeWebViewSession(this, context)
                        webViewClient = navigationClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                if (window === popupWebView) closePopup()
                            }
                        }
                    }
                    popupWebView = popup
                    navigationError = null
                    browserContainer.addView(popup, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                    popup.requestFocus()
                    transport.webView = popup
                    message.sendToTarget()
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    fullscreenView = view
                    fullscreenCallback = callback
                    isFullscreen = true
                    (context as? Activity)?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }

                override fun onHideCustomView() = exitFullscreen()
            }

            webViewClient = navigationClient()

            browserContainer.addView(this)
            loadUrl(videoUrl)
        }
    }

    DisposableEffect(videoUrl) {
        onDispose {
            disposed.set(true)
            closePopup()
            browserContainer.removeAllViews()
            webView.stopLoading()
            webView.webChromeClient = null
            webView.destroy()
            if (isFullscreen) {
                (context as? Activity)?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isFullscreen && fullscreenView != null) {
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
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0D0D))
                        .statusBarsPadding()
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::handleBack) {
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
                navigationError?.let { message ->
                    Text(message, color = Color.White, modifier = Modifier.padding(12.dp))
                }
                AndroidView(
                    factory = {
                        (browserContainer.parent as? ViewGroup)?.removeView(browserContainer)
                        browserContainer
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
