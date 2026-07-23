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
 * Lives in its own composable so flipping the flag only recomposes
 * this function — never the outer RedditLoginScreen that holds the
 * WebView. Keeping the WebView's parent stable prevents focus resets
 * that dismiss the soft keyboard.
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
 *
 * Login detection strategy (2024+ Reddit SPA):
 *  - We do NOT rely on specific cookie names (reddit_session, token_v2…)
 *    because Reddit changes them frequently.
 *  - Instead, whenever onPageFinished fires on a URL that looks like the
 *    post-login feed (not a login/register/oauth2 path), we call
 *    /api/me.json.  Only if that returns a non-blank username do we call
 *    [onLoginSuccess]; otherwise we reset state so the user can retry.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RedditLoginScreen(
    onLoginSuccess: (username: String) -> Unit,
    onBack: () -> Unit,
) {
    val pageLoading   = remember { mutableStateOf(true) }
    var loginDetected by remember { mutableStateOf(false) }
    var fetchingName  by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    // windowSoftInputMode="adjustPan" in AndroidManifest prevents the window
    // from resizing when the keyboard appears — the root cause of WebView
    // focus loss (and keyboard dismissal) in Compose apps.

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
                // /api/me.json shape: {"kind":"t2","data":{"name":"username",...}}
                // Must read data.name, NOT top-level name (which doesn't exist).
                val json = JSONObject(body)
                val name = json.optJSONObject("data")?.optString("name", "").orEmpty()
                    .ifBlank { null }
                name
            }.getOrNull()
        }
        if (name != null) {
            // Confirmed: genuinely logged in.
            onLoginSuccess(name)
        } else {
            // /api/me.json returned nothing — login may not have completed.
            // Reset so the user can finish and trigger detection again.
            fetchingName  = false
            loginDetected = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {

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

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        val wv: WebView = object : WebView(ctx) {
                            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                                super.onWindowFocusChanged(hasWindowFocus)
                                if (!hasWindowFocus) {
                                    val imeVisible = ViewCompat.getRootWindowInsets(this)
                                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                                    if (imeVisible) post { requestFocus() }
                                }
                            }
                        }
                        wv.apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
                                    // Trigger login check once we land on a post-login page.
                                    // We do NOT inspect cookie names here — Reddit changes them.
                                    // The LaunchedEffect will call /api/me.json to verify.
                                    val awayFromLogin = u.contains("reddit.com") &&
                                        !u.contains("/login") &&
                                        !u.contains("/register") &&
                                        !u.contains("/account/register") &&
                                        !u.contains("/oauth2") &&
                                        !u.contains("/verify") &&
                                        !u.contains("/confirm") &&
                                        !u.contains("/two-factor") &&
                                        !u.contains("/challenge")

                                    if (awayFromLogin && !loginDetected && !fetchingName) {
                                        loginDetected = true
                                    }
                                }
                            }

                            loadUrl("https://www.reddit.com/login")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Box(Modifier.fillMaxWidth().align(Alignment.TopStart)) {
                    WebViewProgressBar(pageLoading)
                }
            }
        }

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
