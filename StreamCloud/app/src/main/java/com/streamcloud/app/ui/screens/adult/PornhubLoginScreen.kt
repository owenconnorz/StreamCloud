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
import java.util.concurrent.atomic.AtomicBoolean

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
    var loginFeedback by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val screenDisposed = remember { AtomicBoolean(false) }
    val activeWebViews = remember { mutableSetOf<WebView>() }

    fun destroyWebView(view: WebView?) {
        if (view == null || !activeWebViews.remove(view)) return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.stopLoading() }
        runCatching { view.webChromeClient = null }
        runCatching { view.removeJavascriptInterface("PornhubBridge") }
        runCatching { view.destroy() }
        if (webView === view) webView = null
    }

    fun disposeScreen() {
        if (!screenDisposed.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        activeWebViews.toList().forEach(::destroyWebView)
        webView = null
    }

    fun markProviderLoginStarted() {
        if (screenDisposed.get()) return
        if (!providerLoginStarted) {
            providerCookieBaseline = PornhubRepository.sessionCookieHeader()
            providerLoginStarted = true
        }
    }

    fun detectCompletedProviderLogin(delayMillis: Long = 0L) {
        if (screenDisposed.get() || !providerLoginStarted) return
        mainHandler.postDelayed(
            {
                if (screenDisposed.get()) return@postDelayed
                CookieManager.getInstance().flush()
                val currentCookies = PornhubRepository.sessionCookieHeader()
                if (currentCookies != providerCookieBaseline &&
                    PornhubRepository.hasSessionCookies() &&
                    !providerSessionReturned
                ) {
                    providerSessionReturned = true
                    webView
                        ?.takeIf(activeWebViews::contains)
                        ?.let { view -> runCatching { view.loadUrl("https://www.pornhub.com/") } }
                }
            },
            delayMillis,
        )
    }

    fun finishOrGoBack() {
        if (screenDisposed.get()) return
        CookieManager.getInstance().flush()
        val loggedIn = canFinish || PornhubRepository.hasSessionCookies()
        disposeScreen()
        if (loggedIn) {
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
                mainHandler.post {
                    if (screenDisposed.get()) return@post
                    canFinish = loggedIn && !verificationRequired
                    if (canFinish) loginFeedback = null
                }
            }

            @JavascriptInterface
            fun receiveLoginSubmitted() {
                mainHandler.post {
                    if (!screenDisposed.get()) {
                        loginFeedback = "Submitting login details…"
                    }
                }
            }

            @JavascriptInterface
            fun receiveLoginRejected() {
                mainHandler.post {
                    if (!screenDisposed.get()) {
                        loginFeedback =
                            "Pornhub did not accept those details. Check the form and try again."
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose(::disposeScreen)
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
                    loginFeedback
                        ?: if (canFinish) "Press Back or Done to reload Pornhub"
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
                        activeWebViews += view
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
                            isDisposed = screenDisposed::get,
                            isActive = activeWebViews::contains,
                            onPopupActive = { popup ->
                                activeWebViews += popup
                                if (screenDisposed.get()) {
                                    destroyWebView(popup)
                                } else {
                                    webView = popup
                                }
                            },
                            onPopupDestroyed = ::destroyWebView,
                            onPopupClosed = {
                                if (!screenDisposed.get()) {
                                    webView = view
                                    detectCompletedProviderLogin()
                                    detectCompletedProviderLogin(250L)
                                    detectCompletedProviderLogin(1_000L)
                                }
                            },
                            onProviderStarted = ::markProviderLoginStarted,
                            onProviderReturned = ::detectCompletedProviderLogin,
                            onPageError = {
                                if (!screenDisposed.get()) pageError = it
                            },
                            onPageLoading = {
                                if (!screenDisposed.get()) pageLoading = it
                            },
                        )
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                if (screenDisposed.get()) return true
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
                                if (screenDisposed.get()) return
                                pageLoading = true
                                pageError = null
                                loginFeedback = null
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
                                if (screenDisposed.get()) return
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
                                if (screenDisposed.get()) return
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
                                if (screenDisposed.get() ||
                                    view == null ||
                                    !activeWebViews.contains(view)
                                ) {
                                    return
                                }
                                pageLoading = false
                                CookieManager.getInstance().flush()
                                detectCompletedProviderLogin()
                                val currentUrl = url.orEmpty()
                                if (isPornhubHost(runCatching {
                                        Uri.parse(currentUrl)
                                    }.getOrNull())
                                ) {
                                    repairPornhubSsoButtons(view)
                                    attachPornhubLoginFeedback(view)
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
                        container.addView(
                            view,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        view.loadUrl("https://www.pornhub.com/login")
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            val visibleMessage = pageError ?: loginFeedback
            visibleMessage?.let { message ->
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
                        if (pageError != null) {
                            IconButton(onClick = {
                                pageError = null
                                webView
                                    ?.takeIf(activeWebViews::contains)
                                    ?.let { view -> runCatching { view.reload() } }
                            }) {
                                Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                            }
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
    isDisposed: () -> Boolean,
    isActive: (WebView) -> Boolean,
    onPopupActive: (WebView) -> Unit,
    onPopupDestroyed: (WebView) -> Unit,
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
        if (isDisposed()) return false
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
                    if (isDisposed() || popupView == null || !isActive(popupView)) return true
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
                    if (isDisposed() ||
                        popupView == null ||
                        !isActive(popupView)
                    ) {
                        return
                    }
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
                    if (isDisposed() ||
                        popupView == null ||
                        !isActive(popupView)
                    ) {
                        return
                    }
                    if (request?.isForMainFrame == true) {
                        onPageError(
                            error?.description?.toString()
                                ?: "The login provider could not load this page.",
                        )
                    }
                }

                override fun onPageFinished(popupView: WebView?, url: String?) {
                    super.onPageFinished(popupView, url)
                    if (isDisposed() ||
                        popupView == null ||
                        !isActive(popupView)
                    ) {
                        return
                    }
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
                (child as? WebView)?.let(onPopupDestroyed)
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
        window?.let(onPopupDestroyed)
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

/**
 * Observe only generic form state. Credentials and page contents stay inside
 * the official WebView; the bridge receives no email, password, or error text.
 */
private fun attachPornhubLoginFeedback(view: WebView?) {
    view?.evaluateJavascript(
        """
        (function() {
          if (window.__streamCloudLoginFeedbackAttached) return;
          window.__streamCloudLoginFeedbackAttached = true;
          var loginAttempted = false;

          function reportFailure() {
            if (!loginAttempted) return;
            var text = document.body ? document.body.innerText : '';
            if (/(incorrect (email|password|login)|invalid (email|password|login)|wrong password|login failed|unable to log in|please try again)/i.test(text)) {
              if (window.PornhubBridge) PornhubBridge.receiveLoginRejected();
            }
          }

          function reportSubmission() {
            loginAttempted = true;
            if (window.PornhubBridge) PornhubBridge.receiveLoginSubmitted();
            window.setTimeout(reportFailure, 900);
            window.setTimeout(reportFailure, 2500);
          }

          document.addEventListener('submit', reportSubmission, true);
          document.addEventListener('click', function(event) {
            var target = event.target;
            while (target && target !== document) {
              var tag = (target.tagName || '').toLowerCase();
              var type = (target.getAttribute('type') || '').toLowerCase();
              var label = (target.innerText || target.value || '').trim();
              if (type === 'submit' ||
                  (tag === 'button' && /^(log in|sign in|continue)$/i.test(label))) {
                reportSubmission();
                break;
              }
              target = target.parentElement;
            }
          }, true);

          if (document.body && window.MutationObserver) {
            new MutationObserver(reportFailure).observe(document.body, {
              childList: true,
              subtree: true,
              characterData: true
            });
          }
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