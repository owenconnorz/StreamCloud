@file:Suppress("DEPRECATION")

package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
      return !/\bverify\s+your\s+age\b/i.test(bodyText) &&
        (!!logoutControl || /\b(log\s*out|logout|sign\s*out)\b/i.test(bodyText));
    })();
""".trimIndent()

private val XFREE_VERIFICATION_PAGE_TEXT = """
    (function() {
      return JSON.stringify(document.body ? document.body.innerText : '');
    })();
""".trimIndent()

// Run only after a user presses the app's Continue button on an AgeVerif
// success page. Let the provider deliver its callback before closing itself.
internal val XFREE_CONTINUE_VERIFICATION = """
    (function() {
      if (!/\baccount\s+connection\s+successful\b/i.test(
          document.body ? document.body.innerText : '')) return false;
      var controls = document.querySelectorAll('button, a, input[type="submit"], [role="button"]');
      for (var i = 0; i < controls.length; i++) {
        var control = controls[i];
        var label = (control.innerText || control.value || control.getAttribute('aria-label') || '').trim();
        if (/^continue$/i.test(label) && !control.disabled &&
            control.getAttribute('aria-disabled') !== 'true' && control.getClientRects().length) {
          control.click();
          return true;
        }
      }
      return false;
    })();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XFreeLoginScreen(
    onLoginAttemptStarted: () -> Unit,
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var canFinish by remember { mutableStateOf(false) }
    var canReturn by remember { mutableStateOf(false) }
    var verificationReadyToContinue by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var verificationWebView by remember { mutableStateOf<WebView?>(null) }
    var continuationAttempt by remember { mutableIntStateOf(0) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    val screenDisposed = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        onLoginAttemptStarted()
    }

    fun destroyWebView(view: WebView?) {
        if (view == null) return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.stopLoading() }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.destroy() }
        if (webView === view) webView = null
        if (popupWebView === view) popupWebView = null
        if (verificationWebView === view) {
            verificationWebView = null
            verificationReadyToContinue = false
        }
    }

    fun disposeScreen() {
        if (!screenDisposed.compareAndSet(false, true)) return
        destroyWebView(popupWebView)
        destroyWebView(webView)
    }

    fun continueAfterVerification() {
        if (!verificationReadyToContinue || screenDisposed.get()) return
        val verifiedView = verificationWebView ?: return
        if (!isXFreeVerificationHost(
                runCatching { Uri.parse(verifiedView.url.orEmpty()).host }.getOrNull(),
            )) return
        // Do not navigate to /login or destroy this WebView here: either action
        // can discard the provider's result before XFree receives it.
        continuationAttempt++
        verifiedView.evaluateJavascript(XFREE_CONTINUE_VERIFICATION) { result ->
            if (!screenDisposed.get() && result != "true") {
                pageError = "Tap Continue inside the verification page to complete XFree’s callback."
            }
        }
    }

    fun canConfirmSession(view: WebView?, host: String?, result: String?): Boolean =
        !screenDisposed.get() &&
            view != null &&
            view === (popupWebView ?: webView) &&
            runCatching { Uri.parse(view.url.orEmpty()).host }.getOrNull() == host &&
            hasConfirmedXFreeSession(host, result == "true")

    fun finishOrGoBack() {
        if (screenDisposed.get()) return
        CookieManager.getInstance().flush()
        val signedIn = canFinish
        disposeScreen()
        if (signedIn) onLoginSuccess() else onBack()
    }

    fun handleBack() {
        val popup = popupWebView
        if (popup != null) {
            destroyWebView(popup)
            pageLoading = false
        } else {
            finishOrGoBack()
        }
    }

    BackHandler(onBack = ::handleBack)
    DisposableEffect(Unit) {
        onDispose(::disposeScreen)
    }

    LaunchedEffect(continuationAttempt) {
        if (continuationAttempt == 0) return@LaunchedEffect
        val pendingView = verificationWebView
        delay(12_000)
        if (!screenDisposed.get() && pendingView != null &&
            verificationWebView === pendingView && verificationReadyToContinue
        ) {
            pageError = "The verification page has not returned to XFree. Tap its own Continue button. StreamCloud has not confirmed the session."
        }
    }

    fun createXFreeLoginWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            if (screenDisposed.get()) return true
            val uri = request?.url ?: return true
            if (uri.scheme.equals("about", ignoreCase = true)) return false
            val allowed = isAllowedXFreeLoginNavigation(
                scheme = uri.scheme,
                host = uri.host,
                isMainFrame = request.isForMainFrame,
            )
            if (!allowed && request.isForMainFrame) {
                // Do not expose callback URLs, which can contain credentials.
                pageError = "XFree’s sign-in tried to open an unsupported destination. The return was blocked."
            }
            return !allowed
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
            canFinish = false
            if (verificationWebView === view) {
                verificationReadyToContinue = false
                verificationWebView = null
            }
            val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
            val isLoginPage = uri?.path?.trimEnd('/') == "/login"
            if (isXFreeLoginHost(uri?.host)) {
                verificationReadyToContinue = false
                verificationWebView = null
            }
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
                    ?: "XFree could not load this page."
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
            val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return
            if (!isXFreeLoginHost(uri.host)) return
            canReturn = uri.path?.trimEnd('/') != "/login"
            view.evaluateJavascript(XFREE_SIGNED_IN_CHECK) { result ->
                if (canConfirmSession(view, uri.host, result)) canFinish = true
            }
        }
    }

    LaunchedEffect(webViewGeneration, webView, popupWebView, canFinish) {
        while (!canFinish && !screenDisposed.get()) {
            if (!pageLoading) {
                listOfNotNull(popupWebView ?: webView).forEach { activeWebView ->
                    val currentUri = runCatching {
                        Uri.parse(activeWebView.url.orEmpty())
                    }.getOrNull()
                    when {
                        isXFreeLoginHost(currentUri?.host) -> {
                            activeWebView.evaluateJavascript(XFREE_SIGNED_IN_CHECK) { result ->
                                if (
                                    canConfirmSession(activeWebView, currentUri?.host, result)
                                ) {
                                    canFinish = true
                                }
                            }
                        }

                        isXFreeVerificationHost(currentUri?.host) -> {
                            activeWebView.evaluateJavascript(XFREE_VERIFICATION_PAGE_TEXT) { result ->
                                val pageText = runCatching {
                                    org.json.JSONTokener(result.orEmpty()).nextValue() as? String
                                }.getOrNull()
                                if (
                                    !screenDisposed.get() &&
                                    activeWebView === (popupWebView ?: webView) &&
                                    runCatching { Uri.parse(activeWebView.url.orEmpty()).host }.getOrNull() == currentUri?.host &&
                                    isXFreeVerificationSuccessPage(currentUri?.host, pageText)
                                ) {
                                    // This page may be a popup that closes on Continue,
                                    // or a main-frame page if the provider fell back.
                                    verificationWebView = activeWebView
                                    verificationReadyToContinue = true
                                }
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
        // Return only after XFree itself confirms an authenticated session.
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
            IconButton(onClick = ::handleBack) {
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
                        verificationReadyToContinue ->
                            "Continue on this page to finish linking with XFree."
                        canReturn -> "Return when finished signing in"
                        else -> "Use XFree’s official page to sign in"
                    },
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (canFinish || canReturn || verificationReadyToContinue) {
                TextButton(
                    onClick = {
                        if (verificationReadyToContinue) {
                            continueAfterVerification()
                        } else {
                            finishOrGoBack()
                        }
                    },
                    enabled = !pageLoading,
                ) {
                    Text(
                        when {
                            canFinish -> "Done"
                            verificationReadyToContinue -> "Continue"
                            else -> "Return"
                        },
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
                        val container = FrameLayout(context)
                        val mainView = WebView(context)
                        mainView.apply {
                            webView = this
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(true)
                            configureXFreeWebViewSession(this, context)
                            webViewClient = createXFreeLoginWebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                @Suppress("UNUSED_PARAMETER")
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    if (screenDisposed.get() || view !== mainView) return false
                                    val message = resultMsg ?: return false
                                    val transport =
                                        message.obj as? WebView.WebViewTransport ?: return false
                                    popupWebView?.let(::destroyWebView)
                                    val popup = WebView(context).apply {
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.mixedContentMode =
                                            WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        settings.javaScriptCanOpenWindowsAutomatically = true
                                        settings.setSupportMultipleWindows(false)
                                        configureXFreeWebViewSession(this, context)
                                        webViewClient = createXFreeLoginWebViewClient()
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCloseWindow(window: WebView?) {
                                                if (window === popupWebView) {
                                                    destroyWebView(window)
                                                    pageLoading = false
                                                }
                                            }
                                        }
                                    }
                                    popupWebView = popup
                                    canFinish = false
                                    container.addView(
                                        popup,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                    container.bringChildToFront(popup)
                                    transport.webView = popup
                                    message.sendToTarget()
                                    return true
                                }
                            }
                        }
                        container.addView(
                            mainView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        mainView.loadUrl("https://www.xfree.com/login?lang=en-US")
                        container
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
                            val activeWebView = popupWebView ?: webView
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

internal fun hasConfirmedXFreeSession(
    host: String?,
    hasSignedOutControl: Boolean,
): Boolean = isXFreeLoginHost(host) && hasSignedOutControl

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
