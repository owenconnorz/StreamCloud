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

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url == null) return

                // After the user signs in, Spotify redirects to open.spotify.com
                if (!url.contains("open.spotify.com")) return

                val rawCookie = CookieManager.getInstance()
                    .getCookie("https://open.spotify.com") ?: return
                if (rawCookie.isBlank()) return

                val spDc = rawCookie.split(";")
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("sp_dc=") }
                    ?.substringAfter("sp_dc=")
                    ?.trim()
                if (spDc.isNullOrBlank()) return

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
