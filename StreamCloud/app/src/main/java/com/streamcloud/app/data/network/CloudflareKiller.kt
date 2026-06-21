package com.streamcloud.app.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object CloudflareKiller {

    fun isCfChallenge(code: Int, headers: Map<String, List<String>>, body: String): Boolean {
        if (code != 403 && code != 503) return false
        val hasCfRay = headers.any { it.key.equals("cf-ray", ignoreCase = true) }
        val hasCfBody =
            body.contains("_cf_chl_opt", ignoreCase = true) ||
            body.contains("cf-browser-verification", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true)
        return hasCfRay || hasCfBody
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun bypass(
        context: Context,
        url: String,
        userAgent: String,
        cookieJar: BrowserCookieJar,
    ): Boolean = withContext(Dispatchers.Main) {
        val host = runCatching { java.net.URI(url).host }
            .getOrElse { null } ?: return@withContext false

        var resolved = false
        var webView: WebView? = null

        try {
            webView = WebView(context.applicationContext)
            webView.settings.apply {
                javaScriptEnabled = true
                userAgentString = userAgent
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    val title = view.title ?: return
                    if (title.isNotBlank() &&
                        !title.contains("just a moment", ignoreCase = true) &&
                        !title.contains("please wait", ignoreCase = true) &&
                        !title.contains("one moment", ignoreCase = true)) {
                        resolved = true
                    }
                }
            }

            webView.loadUrl(url)

            withTimeoutOrNull(12_000L) {
                while (!resolved) delay(250)
            }

            if (resolved) {
                val raw = cm.getCookie(url) ?: ""
                raw.split(";").forEach { part ->
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val name  = part.substring(0, eq).trim()
                        val value = part.substring(eq + 1).trim()
                        if (name.isNotEmpty()) cookieJar.setCookie(host, name, value)
                    }
                }
            }
        } finally {
            webView?.destroy()
        }

        resolved
    }
}
