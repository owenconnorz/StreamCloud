@file:Suppress("DEPRECATION")
package com.streamcloud.app.ui.screens.adult

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    var pageLoading    by remember { mutableStateOf(true) }
    var loginDetected  by remember { mutableStateOf(false) }
    var fetchingName   by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

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

            if (pageLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFF4500),
                )
            }

            // ── WebView ──────────────────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {

                            override fun shouldOverrideUrlLoading(
                                view: WebView?, request: WebResourceRequest?,
                            ) = false  // keep navigation inside the WebView

                            override fun onPageStarted(
                                view: WebView?, url: String?, favicon: Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                pageLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoading = false

                                // Flush cookies to persistent storage immediately.
                                CookieManager.getInstance().flush()

                                val u = url ?: return
                                // Login is complete when Reddit redirects away from /login.
                                val awayFromLogin = u.contains("reddit.com") &&
                                    !u.contains("/login") &&
                                    !u.contains("/register") &&
                                    !u.contains("/account/register")

                                if (awayFromLogin && !loginDetected) {
                                    val cookies = CookieManager.getInstance()
                                        .getCookie("https://www.reddit.com").orEmpty()
                                    // Only flag as logged-in when a real session cookie exists.
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
