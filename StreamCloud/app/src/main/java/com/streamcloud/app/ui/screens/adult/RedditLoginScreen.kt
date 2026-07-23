@file:Suppress("DEPRECATION")
package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.CookieManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads [loading] and shows a progress bar only while loading.
 * Placed in its own composable so that flipping the flag ONLY recomposes
 * this function — never the outer RedditLoginScreen that contains the WebView.
 * Keeping WebView's parent stable prevents the focus-reset that dismisses
 * the soft keyboard.
 */
@Composable
private fun WebViewProgressBar(loading: State<Boolean>) {
    if (loading.value) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFFF4500),
        )
    }
}

/**
 * Full-screen WebView that lets the user log in to Reddit.
 * Android's CookieManager automatically persists the session cookies to disk,
 * so subsequent API calls in [RedditAdultRepository] that read those cookies
 * will be authenticated — fixing 404 errors on NSFW subreddits.
 *
 * Calls [onLoginSuccess] with the Reddit username (or "reddit_user" if
 * the /api/me.json fetch fails).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RedditLoginScreen(
    onLoginSuccess: (username: String) -> Unit,
    onBack: () -> Unit,
) {
    // NOTE: pageLoading is a MutableState but its VALUE (.value) is never READ
    // inside RedditLoginScreen's own composition scope — only the child
    // WebViewProgressBar reads it. This means pageLoading changes do NOT
    // recompose RedditLoginScreen, so the AndroidView/WebView is never
    // disturbed and the keyboard stays up.
    val pageLoading = remember { mutableStateOf(true) }
    var loginDetected  by remember { mutableStateOf(false) }
    var fetchingName   by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    // windowSoftInputMode="adjustPan" in AndroidManifest prevents the window
    // from resizing when the keyboard appears, which is the primary cause of
    // WebView focus loss (and therefore keyboard dismissal) in Compose apps.

    // Once WebView signals a successful login, fetch the username in the background.
    LaunchedEffect(loginDetected) {
        if (!loginDetected || fetchingName) return@LaunchedEffect
        fetchingName = true
        val name = withContext(Dispatchers.IO) {
            runCatching {
                val cookie = CookieManager.getInstance()
                    .getCookie("https://www.reddit.com").orEmpty()
                val conn = URL("https://www.reddit.com/api/me.json")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty(
                    "User-Agent",
                    "android:com.streamcloud.app:v1.0.0 (by /u/streamcloud_app)"
                )
                conn.connectTimeout = 8_000
                conn.readTimeout    = 8_000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                JSONObject(body).optString("name", "").ifBlank { null }
            }.getOrNull() ?: "reddit_user"
        }
        onLoginSuccess(name)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ──────────────────────────────────────────────────
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
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }

            // ── WebView + isolated progress bar overlay ───────────────────
            // The progress bar is rendered by WebViewProgressBar (a separate
            // @Composable) so its show/hide recomposes only that child —
            // never this Column or the AndroidView. WebView focus is preserved.
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        // Anonymous subclass guards against focus loss while the IME
                        // is visible — a known Compose + AndroidView interaction where
                        // recomposition or pointer-event handling can call clearFocus()
                        // on the view tree, dismissing the soft keyboard.
                        object : WebView(ctx) {
                            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                                super.onWindowFocusChanged(hasWindowFocus)
                                // If the window just lost focus AND the IME is currently
                                // shown (user was typing), re-request focus on the next
                                // frame so the keyboard stays up.
                                if (!hasWindowFocus) {
                                    val imeVisible = ViewCompat.getRootWindowInsets(this)
                                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                                    if (imeVisible) post { requestFocus() }
                                }
                            }
                        }.apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            // Claim focus up-front so the first tap on any input
                            // field opens the keyboard immediately.
                            requestFocus()

                            webViewClient = object : WebViewClient() {

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?, request: WebResourceRequest?,
                                ) = false

                                override fun onPageStarted(
                                    view: WebView?, url: String?, favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    pageLoading.value = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    pageLoading.value = false
                                    CookieManager.getInstance().flush()

                                    val u = url ?: return
                                    // Only consider it a successful post-login redirect when
                                    // the URL looks like the main Reddit feed, a subreddit, or
                                    // the user profile — NOT an OAuth grant page or email step.
                                    val awayFromLogin = u.contains("reddit.com") &&
                                        !u.contains("/login") &&
                                        !u.contains("/register") &&
                                        !u.contains("/account/register") &&
                                        !u.contains("/oauth2") &&
                                        !u.contains("/verify") &&
                                        !u.contains("/confirm") &&
                                        !u.contains("/two-factor") &&
                                        !u.contains("/challenge")

                                    if (awayFromLogin && !loginDetected) {
                                        val cookies = CookieManager.getInstance()
                                            .getCookie("https://www.reddit.com").orEmpty()
                                        if (cookies.contains("reddit_session") ||
                                            cookies.contains("token_v2") ||
                                            cookies.contains("accessToken") ||
                                            cookies.contains("session_tracker")) {
                                            loginDetected = true
                                        }
                                    }
                                }
                            }

                            loadUrl("https://www.reddit.com/login")
                        }
                    }.also { /* WebView setup complete */ },
                    modifier = Modifier.fillMaxSize(),
                )

                // Child composable — only IT recomposes when pageLoading flips.
                // RedditLoginScreen itself is NOT recomposed, so WebView focus
                // and the soft keyboard are never disturbed.
                Box(Modifier.fillMaxWidth().align(Alignment.TopStart)) {
                    WebViewProgressBar(pageLoading)
                }
            }
        }

        // Spinner shown while fetching username after login
        if (fetchingName) {
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
