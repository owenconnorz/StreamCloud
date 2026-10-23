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
import com.streamcloud.app.data.api.configureXFreeWebViewSession
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
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private val XFREE_SIGNED_IN_CHECK = """
    (function() {
      var bodyText = document.body ? document.body.innerText : '';
      var logoutControl = document.querySelector(
        'a[href*="logout" i], a[href*="signout" i], ' +
        'button[aria-label*="log out" i], button[aria-label*="sign out" i], ' +
        '[data-testid*="logout" i], [data-role*="logout" i]'
      );
      return !!logoutControl ||
        /\b(log\s*out|logout|sign\s*out)\b/i.test(bodyText);
    })();
""".trimIndent()

private val XFREE_VERIFICATION_PAGE_TEXT = """
    (function() {
      return JSON.stringify(document.body ? document.body.innerText : '');
    })();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XFreeLoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var canFinish by remember { mutableStateOf(false) }
    var canReturn by remember { mutableStateOf(false) }
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

    LaunchedEffect(webViewGeneration, webView, canFinish) {
        while (!canFinish && !screenDisposed.get()) {
            val activeWebView = webView
            val currentUri = runCatching {
                Uri.parse(activeWebView?.url.orEmpty())
            }.getOrNull()
            if (!pageLoading && activeWebView != null) {
                when {
                    isXFreeLoginHost(currentUri?.host) -> {
                        activeWebView.evaluateJavascript(XFREE_SIGNED_IN_CHECK) { result ->
                            if (!screenDisposed.get() && result == "true") canFinish = true
                        }
                    }

                    isXFreeVerificationHost(currentUri?.host) -> {
                        activeWebView.evaluateJavascript(XFREE_VERIFICATION_PAGE_TEXT) { result ->
                            val pageText = runCatching {
                                org.json.JSONTokener(result.orEmpty()).nextValue() as? String
                            }.getOrNull()
                            if (
                                !screenDisposed.get() &&
                                isXFreeVerificationSuccessPage(currentUri?.host, pageText)
                            ) {
                                canFinish = true
                            }
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    LaunchedEffect(canFinish) {
        if (!canFinish) return@LaunchedEffect
        // AgeVerif's success page says it will close automatically, but WebView
        // does not consistently honor that request. Complete the same verified
        // flow in-app after allowing the success state to render briefly.
        delay(900)
        finishOrGoBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616))
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
                    if (canFinish) "Signed in to XFree" else "Sign in to XFree",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        canFinish -> "Press Back or Done to return"
                        canReturn -> "Return when finished signing in"
                        else -> "Use XFree’s official page to sign in"
                    },
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (canFinish || canReturn) {
                TextButton(
                    onClick = {
                        finishOrGoBack()
                    },
                    enabled = !pageLoading,
                ) {
                    Text(
                        if (canFinish) "Done" else "Return",
                        color = Color(0xFF4F8DFF),
                    )
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
                            view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            view.settings.javaScriptCanOpenWindowsAutomatically = false
                            view.settings.setSupportMultipleWindows(false)
                            configureXFreeWebViewSession(view, context)
                            view.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    if (screenDisposed.get()) return true
                                    val uri = request?.url ?: return true
                                    if (uri.scheme.equals("about", ignoreCase = true)) return false
                                    return !isAllowedXFreeLoginNavigation(
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
                                    val isLoginPage = uri?.path?.trimEnd('/') == "/login"
                                    canReturn = isXFreeLoginHost(uri?.host) && !isLoginPage
                                    if (isLoginPage) {
                                        canFinish = false
                                        canReturn = false
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (!screenDisposed.get() && request?.isForMainFrame == true) {
                                        pageError = error?.description?.toString()
                                            ?: "XFree could not load the sign-in page."
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    response: android.webkit.WebResourceResponse?,
                                ) {
                                    super.onReceivedHttpError(view, request, response)
                                    if (!screenDisposed.get() && request?.isForMainFrame == true) {
                                        pageError = when (response?.statusCode) {
                                            403 -> "XFree blocked this page. Follow its verification steps and retry."
                                            429 -> "XFree is limiting requests. Wait a moment and retry."
                                            else -> "XFree returned HTTP ${response?.statusCode ?: "an error"}."
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
                                        pageError = "XFree’s sign-in page stopped. Tap Retry to reopen it."
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
                                    if (!isXFreeLoginHost(uri.host)) return
                                    canReturn = uri.path?.trimEnd('/') != "/login"
                                    if (!canReturn) return
                                    view.evaluateJavascript(XFREE_SIGNED_IN_CHECK) { result ->
                                        if (!screenDisposed.get()) canFinish = result == "true"
                                    }
                                }
                            }
                            view.loadUrl("https://www.xfree.com/login?lang=en-US")
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

internal fun isXFreeLoginHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trimEnd('.') ?: return false
    return normalized == "xfree.com" || normalized.endsWith(".xfree.com")
}

private fun isXFreeIdentityHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trimEnd('.') ?: return false
    return normalized in setOf(
        "accounts.google.com",
        "appleid.apple.com",
        "idmsa.apple.com",
    )
}

internal fun isXFreeVerificationHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.trimEnd('.') ?: return false
    return normalized == "ageverif.com" || normalized.endsWith(".ageverif.com")
}

internal fun isXFreeVerificationSuccessPage(host: String?, pageText: String?): Boolean {
    if (!isXFreeVerificationHost(host)) return false
    return Regex(
        """\baccount\s+connection\s+successful\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(pageText.orEmpty())
}

internal fun isAllowedXFreeLoginNavigation(
    scheme: String?,
    host: String?,
    isMainFrame: Boolean,
): Boolean {
    if (!scheme.equals("https", ignoreCase = true)) return false
    return !isMainFrame ||
        isXFreeLoginHost(host) ||
        isXFreeIdentityHost(host) ||
        isXFreeVerificationHost(host)
}
