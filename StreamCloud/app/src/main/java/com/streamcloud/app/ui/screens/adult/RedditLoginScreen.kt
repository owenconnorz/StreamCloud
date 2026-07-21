@file:Suppress("DEPRECATION")
package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
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

    // enableEdgeToEdge() in MainActivity calls setDecorFitsSystemWindows(false)
    // which silently overrides any manifest windowSoftInputMode. Set it
    // programmatically for this screen and restore it when we leave.
    val activity = LocalContext.current as Activity
    DisposableEffect(Unit) {
        val prev = activity.window.attributes.softInputMode
        @Suppress("DEPRECATION")
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { activity.window.setSoftInputMode(prev) }
    }

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
                        WebView(ctx).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

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
                                    val awayFromLogin = u.contains("reddit.com") &&
                                        !u.contains("/login") &&
                                        !u.contains("/register") &&
                                        !u.contains("/account/register")

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
                    },
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
