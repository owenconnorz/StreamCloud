@file:Suppress("DEPRECATION")

package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.streamcloud.app.data.api.PornhubRepository

/**
 * Official Pornhub login flow. StreamCloud never receives the password or
 * verification codes; Android WebView owns the page and its cookie jar.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PornhubLoginScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var canFinish by remember { mutableStateOf(false) }
    var providerLoginStarted by remember { mutableStateOf(false) }
    var providerCookieBaseline by remember { mutableStateOf("") }
    var providerSessionReturned by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun markProviderLoginStarted() {
        if (!providerLoginStarted) {
            providerCookieBaseline = PornhubRepository.sessionCookieHeader()
            providerLoginStarted = true
        }
    }

    fun detectCompletedProviderLogin(delayMillis: Long = 0L) {
        if (!providerLoginStarted) return
        mainHandler.postDelayed(
            {
                CookieManager.getInstance().flush()
                val currentCookies = PornhubRepository.sessionCookieHeader()
                if (currentCookies != providerCookieBaseline &&
                    PornhubRepository.hasSessionCookies() &&
                    !providerSessionReturned
                ) {
                    providerSessionReturned = true
                    webView?.loadUrl("https://www.pornhub.com/")
                }
            },
            delayMillis,
        )
    }

    fun finishOrGoBack() {
        CookieManager.getInstance().flush()
        if (canFinish || PornhubRepository.hasSessionCookies()) {
            onLoginSuccess()
        } else {
            onBack()
        }
    }

    BackHandler(onBack = ::finishOrGoBack)

    val bridge = remember {
        object {
            @JavascriptInterface
            fun receivePageState(loggedIn: Boolean, verificationRequired: Boolean) {
                Handler(Looper.getMainLooper()).post {
                    canFinish = loggedIn && !verificationRequired
                }
            }
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
                    if (canFinish) "Signed in to Pornhub" else "Sign in to Pornhub",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                        .copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    if (canFinish) "Press Back or Done to reload Pornhub"
                    else "Complete verification on Pornhub’s official page",
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (canFinish) {
                TextButton(
                    onClick = ::finishOrGoBack,
                    enabled = !pageLoading,
                ) {
                    Text("Done", color = Color(0xFFFFA726))
                }
            }
        }

        if (pageLoading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = Color(0xFFFFA726),
            )
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    FrameLayout(context).also { container ->
                        WebView(context).also { view ->
                        webView = view
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        view.settings.javaScriptEnabled = true
                        view.settings.domStorageEnabled = true
                        view.settings.useWideViewPort = true
                        view.settings.loadWithOverviewMode = true
                        view.settings.javaScriptCanOpenWindowsAutomatically = true
                        view.settings.setSupportMultipleWindows(true)
                        // Pornhub's SSO markup is served differently to the
                        // Android WebView user agent. A current mobile Chrome
                        // UA also keeps the Google sign-in control visible.
                        view.settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/124.0.0.0 Mobile Safari/537.36"

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                        view.addJavascriptInterface(bridge, "PornhubBridge")
                        view.webChromeClient = visiblePopupClient(
                            container = container,
                            parent = view,
                            bridge = bridge,
                            onPopupActive = { popup -> webView = popup },
                            onPopupClosed = {
                                webView = view
                                detectCompletedProviderLogin()
                                detectCompletedProviderLogin(250L)
                                detectCompletedProviderLogin(1_000L)
                            },
                            onProviderStarted = ::markProviderLoginStarted,
                            onProviderReturned = ::detectCompletedProviderLogin,
                            onPageError = { pageError = it },
                            onPageLoading = { pageLoading = it },
                        )
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val uri = request?.url ?: return true
                                if (uri.scheme == "about") {
                                    detectCompletedProviderLogin()
                                    return false
                                }
                                if (isLoginProviderHost(uri)) {
                                    markProviderLoginStarted()
                                }
                                if (isPornhubHost(uri)) {
                                    detectCompletedProviderLogin()
                                }
                                return !isAllowedLoginNavigation(uri)
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                pageLoading = true
                                pageError = null
                                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                if (isLoginProviderHost(uri)) {
                                    markProviderLoginStarted()
                                }
                                if (isPornhubHost(uri)) {
                                    detectCompletedProviderLogin()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    pageError = error?.description?.toString()
                                        ?: "Pornhub could not load this page."
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                response: android.webkit.WebResourceResponse?,
                            ) {
                                super.onReceivedHttpError(view, request, response)
                                if (request?.isForMainFrame == true) {
                                    pageError = when (response?.statusCode) {
                                        403 -> "Pornhub blocked this page. Complete verification and retry."
                                        429 -> "Pornhub is rate-limiting this device. Please wait and retry."
                                        else -> "Pornhub returned HTTP ${response?.statusCode ?: "an error"}."
                                    }
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoading = false
                                CookieManager.getInstance().flush()
                                detectCompletedProviderLogin()
                                val currentUrl = url.orEmpty()
                                if (isPornhubHost(runCatching {
                                        Uri.parse(currentUrl)
                                    }.getOrNull())
                                ) {
                                    repairPornhubSsoButtons(view)
                                }
                                val awayFromLogin = isPornhubHost(
                                    runCatching { Uri.parse(currentUrl) }.getOrNull(),
                                ) &&
                                    !currentUrl.contains("/login", ignoreCase = true)
                                canFinish = false
                                if (awayFromLogin) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                          var t = document.body ? document.body.innerText : '';
                                          var loggedIn =
                                            /(^|\n)\s*(log out|logout|sign out)\b/i.test(t);
                                          var verificationRequired =
                                            /(verify your age|age verification|confirm your age|age assurance)/i.test(t);
                                          PornhubBridge.receivePageState(
                                            loggedIn,
                                            verificationRequired
                                          );
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }
                            }
                        }
                        view.loadUrl("https://www.pornhub.com/login")
                        container.addView(
                            view,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            pageError?.let { message ->
                Surface(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = Color(0xFF2A1717),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
                            webView?.reload()
                        }) {
                            Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun visiblePopupClient(
    container: FrameLayout,
    parent: WebView,
    bridge: Any,
    onPopupActive: (WebView) -> Unit,
    onPopupClosed: () -> Unit,
    onProviderStarted: () -> Unit,
    onProviderReturned: (Long) -> Unit,
    onPageError: (String) -> Unit,
    onPageLoading: (Boolean) -> Unit,
): WebChromeClient = object : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val message = resultMsg ?: return false
        val transport = message.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(parent.context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = parent.settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(bridge, "PornhubBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    popupView: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val uri = request?.url ?: return true
                    if (uri.scheme == "about") {
                        onProviderReturned(0L)
                        return false
                    }
                    if (!isAllowedLoginNavigation(uri)) {
                        onPageError("The login provider opened an unsupported page.")
                        return true
                    }
                    if (isLoginProviderHost(uri)) onProviderStarted()
                    if (isPornhubHost(uri)) onProviderReturned(0L)
                    return false
                }

                override fun onPageStarted(
                    popupView: WebView?,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    super.onPageStarted(popupView, url, favicon)
                    onPageLoading(true)
                    val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                    if (isLoginProviderHost(uri)) onProviderStarted()
                    if (isPornhubHost(uri)) onProviderReturned(0L)
                }

                override fun onReceivedError(
                    popupView: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(popupView, request, error)
                    if (request?.isForMainFrame == true) {
                        onPageError(
                            error?.description?.toString()
                                ?: "The login provider could not load this page.",
                        )
                    }
                }

                override fun onPageFinished(popupView: WebView?, url: String?) {
                    super.onPageFinished(popupView, url)
                    onPageLoading(false)
                    CookieManager.getInstance().flush()
                    onProviderReturned(0L)
                    val currentUrl = url.orEmpty()
                    if (isPornhubHost(runCatching {
                            Uri.parse(currentUrl)
                        }.getOrNull())
                    ) {
                        repairPornhubSsoButtons(popupView)
                        evaluatePornhubPageState(popupView, currentUrl)
                    }
                }
            }
        }

        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index)
            if (child !== parent) {
                container.removeViewAt(index)
                (child as? WebView)?.destroy()
            }
        }
        container.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        onPopupActive(popup)
        transport.webView = popup
        message.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView?) {
        window?.let {
            container.removeView(it)
            it.destroy()
        }
        onPopupClosed()
    }
}

private fun evaluatePornhubPageState(view: WebView?, currentUrl: String) {
    val awayFromLogin = isPornhubHost(
        runCatching { Uri.parse(currentUrl) }.getOrNull(),
    ) && !currentUrl.contains("/login", ignoreCase = true)
    if (!awayFromLogin) return

    view?.evaluateJavascript(
        """
        (function() {
          var t = document.body ? document.body.innerText : '';
          var loggedIn = /(^|\n)\s*(log out|logout|sign out)\b/i.test(t);
          var verificationRequired =
            /(verify your age|age verification|confirm your age|age assurance)/i.test(t);
          PornhubBridge.receivePageState(loggedIn, verificationRequired);
        })();
        """.trimIndent(),
        null,
    )
}

private fun isPornhubHost(uri: Uri?): Boolean {
    val host = uri?.host?.lowercase() ?: return false
    return host == "pornhub.com" || host.endsWith(".pornhub.com")
}

/**
 * Pornhub's SSO buttons leave Pornhub briefly and return after the provider
 * finishes authentication. Keep this list deliberately narrow: normal page
 * navigation must still stay on Pornhub, while Google and X OAuth pages need
 * to be allowed inside the official WebView.
 */
private fun isLoginProviderHost(uri: Uri?): Boolean {
    val host = uri?.host?.lowercase() ?: return false
    return host == "accounts.google.com" ||
        host == "google.com" ||
        host.endsWith(".google.com") ||
        host == "googleapis.com" ||
        host.endsWith(".googleapis.com") ||
        host == "googleusercontent.com" ||
        host.endsWith(".googleusercontent.com") ||
        host == "gstatic.com" ||
        host.endsWith(".gstatic.com") ||
        host == "x.com" ||
        host.endsWith(".x.com") ||
        host == "twitter.com" ||
        host.endsWith(".twitter.com")
}

private fun isAllowedLoginNavigation(uri: Uri?): Boolean =
    uri?.scheme == "https" && (isPornhubHost(uri) || isLoginProviderHost(uri))

/**
 * Pornhub's responsive stylesheet can hide the SSO labels and external Google
 * image in Android WebView, leaving an apparently empty button. Restore only
 * the presentation of Pornhub's own existing buttons; their official click
 * handlers and authentication flow remain untouched.
 */
private fun repairPornhubSsoButtons(view: WebView?) {
    view?.evaluateJavascript(
        """
        (function() {
          function repair(id, label) {
            // Pornhub currently renders duplicate desktop/mobile controls with
            // the same ID. Repair every instance, not only getElementById().
            var buttons = document.querySelectorAll('[id="' + id + '"]');
            buttons.forEach(function(button) {
              button.style.setProperty('display', 'flex', 'important');
              button.style.setProperty('align-items', 'center', 'important');
              button.style.setProperty('justify-content', 'center', 'important');
              button.style.setProperty('gap', '8px', 'important');
              button.style.setProperty('color', '#ffffff', 'important');
              button.style.setProperty('font-size', '16px', 'important');

              // Pornhub mobile CSS explicitly hides all spans in these buttons.
              // Use <b> so the visible label survives without replacing the
              // official button or its attached click handler.
              var text = button.querySelector('b[data-streamcloud-sso-label]');
              if (!text) {
                text = document.createElement('b');
                text.setAttribute('data-streamcloud-sso-label', 'true');
                button.appendChild(text);
              }
              if (text.textContent !== label) {
                text.textContent = label;
              }
              text.style.setProperty('display', 'inline', 'important');
              text.style.setProperty('visibility', 'visible', 'important');
              text.style.setProperty('opacity', '1', 'important');
              text.style.setProperty('color', '#ffffff', 'important');
              text.style.setProperty('font-size', '16px', 'important');
              text.style.setProperty('font-weight', '600', 'important');

              // Pornhub binds its SSO click listener to the first duplicate.
              // Forward a click from a later visible mobile copy to that
              // canonical control without replacing Pornhub's own handler.
              if (button !== buttons[0] &&
                  button.getAttribute('data-streamcloud-sso-forwarded') !== 'true') {
                button.setAttribute('data-streamcloud-sso-forwarded', 'true');
                button.addEventListener('click', function(event) {
                  event.preventDefault();
                  event.stopImmediatePropagation();
                  buttons[0].click();
                }, true);
              }
            });
          }
          function repairAll() {
            repair('ssoGoogleSigninButton', 'Google');
            repair('ssoXSigninButton', 'X');
          }
          repairAll();

          if (!window.__streamcloudSsoObserver && document.documentElement) {
            window.__streamcloudSsoObserver = new MutationObserver(function() {
              repairAll();
            });
            window.__streamcloudSsoObserver.observe(document.documentElement, {
              childList: true,
              subtree: true
            });
          }
        })();
        """.trimIndent(),
        null,
    )
}