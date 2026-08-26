@file:Suppress("DEPRECATION")
package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
private fun WebViewProgressBar(loading: State<Boolean>) {
    if (loading.value) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color    = Color(0xFFFF4500),
        )
    }
}

/**
 * Full-screen WebView that lets the user sign in to Reddit.
 *
 * Login detection:
 *  1. We do NOT use HttpURLConnection — it cannot replicate the WebView's
 *     cookie jar (HttpOnly cookies, domain variants, etc.).
 *  2. Instead, after landing on a post-login Reddit URL, we inject a
 *     fetch('/api/me.json') call using evaluateJavascript(). Because the
 *     fetch runs inside the WebView, it automatically carries all cookies.
 *  3. The result is returned to Kotlin via a @JavascriptInterface bridge.
 *  4. A LaunchedEffect watches the bridge result and calls onLoginSuccess.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RedditLoginScreen(
    onLoginSuccess: (username: String) -> Unit,
    onBack: () -> Unit,
) {
    val pageLoading = remember { mutableStateOf(true) }
    // The username detected by the JS bridge; null until we get a response.
    val detectedUsername = remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    // Fire onLoginSuccess as soon as the bridge delivers a username.
    LaunchedEffect(detectedUsername.value) {
        val name = detectedUsername.value ?: return@LaunchedEffect
        onLoginSuccess(name)
    }

    // Kotlin ↔ JavaScript bridge.
    // Stable across recompositions so the WebView never loses its reference.
    val bridge = remember {
        object {
            @JavascriptInterface
            fun receiveUsername(name: String) {
                // JavascriptInterface callbacks may arrive on a non-main thread.
                Handler(Looper.getMainLooper()).post {
                    if (name.isNotBlank()) detectedUsername.value = name
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
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
                    "Sign in to Reddit",
                    color    = Color.White,
                    style    = MaterialTheme.typography.titleSmall
                        .copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }

            // ── WebView ────────────────────────────────────────────────────
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        val wv = WebView(ctx)
                        wv.apply {
                            isFocusable             = true
                            isFocusableInTouchMode  = true
                            settings.javaScriptEnabled  = true
                            settings.domStorageEnabled  = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort    = true

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            // Bridge must be added before loadUrl.
                            addJavascriptInterface(bridge, "RedditBridge")

                            webViewClient = object : WebViewClient() {

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ) = false

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    pageLoading.value = true
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    pageLoading.value = false
                                    CookieManager.getInstance().flush()

                                    val u = url ?: return
                                    // Only act when we've navigated AWAY from the login flow.
                                    val awayFromLogin = u.contains("reddit.com") &&
                                        !u.contains("/login") &&
                                        !u.contains("/register") &&
                                        !u.contains("/account/register") &&
                                        !u.contains("/oauth2") &&
                                        !u.contains("/verify") &&
                                        !u.contains("/confirm") &&
                                        !u.contains("/two-factor") &&
                                        !u.contains("/challenge")

                                    if (!awayFromLogin || detectedUsername.value != null) return

                                    // Use the WebView's own fetch() so the request carries
                                    // ALL of Reddit's cookies automatically — no manual cookie
                                    // header construction needed.
                                    view?.evaluateJavascript("""
                                        (function() {
                                            fetch('/api/me.json', {credentials: 'include'})
                                              .then(function(r) { return r.json(); })
                                              .then(function(j) {
                                                var n = (j && j.data && j.data.name)
                                                            ? j.data.name : '';
                                                RedditBridge.receiveUsername(n);
                                              })
                                              .catch(function() {
                                                RedditBridge.receiveUsername('');
                                              });
                                        })();
                                    """.trimIndent()) { /* Promise — result via bridge */ }
                                }
                            }

                            loadUrl("https://www.reddit.com/login")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                ) {
                    WebViewProgressBar(pageLoading)
                }
            }
        }

        // Spinner overlay while verifying login
        if (detectedUsername.value != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFF4500))
                    Spacer(Modifier.height(12.dp))
                    Text("Signing in…", color = Color.White)
                }
            }
        }
    }
}
