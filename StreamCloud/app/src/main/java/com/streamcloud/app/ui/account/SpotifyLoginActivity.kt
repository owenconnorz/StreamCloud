package com.streamcloud.app.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.spotify.SpotifyCanvasRepository
import kotlinx.coroutines.launch

class SpotifyLoginActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        container.addView(progress)
        container.addView(web)
        setContentView(container)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url == null) return

                // After sign-in Spotify lands on open.spotify.com.
                if (!url.contains("open.spotify.com")) return

                // Build a robust cookie header instead of only getCookie(open.spotify.com).
                // On newer WebView/Spotify flows, critical cookies (sp_dc/sp_key/...) may be
                // scoped across .spotify.com, open.spotify.com, or accounts.spotify.com.
                val cookieDomains = listOf(
                    "https://open.spotify.com",
                    "https://accounts.spotify.com",
                    "https://spotify.com",
                    "https://www.spotify.com",
                )
                val merged = linkedSetOf<String>()
                cookieDomains.forEach { domain ->
                    cm.getCookie(domain)
                        ?.split(';')
                        ?.map { it.trim() }
                        ?.filter { it.contains('=') }
                        ?.forEach { merged.add(it) }
                }
                val rawCookie = merged.joinToString("; ")
                if (rawCookie.isBlank()) return

                // Canvas auth needs sp_dc and often sp_key to mint web tokens.
                val hasSpDc = merged.any { it.startsWith("sp_dc=") }
                val hasSpKey = merged.any { it.startsWith("sp_key=") }
                if (!hasSpDc && !hasSpKey) return

                lifecycleScope.launch {
                    val sl = ServiceLocator.get(applicationContext)
                    sl.settings.setSpotifyCookie(rawCookie)
                    sl.settings.setSpotifyUserName("Logged in")
                    SpotifyCanvasRepository.setSpotifyCookie(rawCookie)
                    finish()
                }
            }
        }

        web.loadUrl(
            "https://accounts.spotify.com/en/login" +
                "?continue=https%3A%2F%2Fopen.spotify.com%2F",
        )
    }
}
