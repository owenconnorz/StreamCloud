package com.streamcloud.app.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.streamcloud.app.data.ServiceLocator
import kotlinx.coroutines.launch

class DiscordLoginActivity : ComponentActivity() {

    private inner class TokenBridge {
        @JavascriptInterface
        fun onTokenFound(token: String) {
            if (token.isBlank() || token.length < 20) return
            lifecycleScope.launch {
                val sl = ServiceLocator.get(applicationContext)
                sl.settings.setDiscordToken(token)
                finish()
            }
        }
    }

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
        web.addJavascriptInterface(TokenBridge(), "DiscordBridge")
        container.addView(progress)
        container.addView(web)
        setContentView(container)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                // Inject interceptor as early as possible so XHR/fetch patches are
                // in place before Discord's JS bundles execute their first API call.
                view?.evaluateJavascript(TOKEN_INTERCEPTOR_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                // Re-inject on finish to catch deferred SPA bootstrap calls and also
                // attempt an immediate localStorage read for older Discord builds.
                view?.evaluateJavascript(TOKEN_INTERCEPTOR_JS, null)
            }
        }

        web.loadUrl("https://discord.com/login")
    }

    companion object {
        /**
         * Patches XMLHttpRequest.setRequestHeader and window.fetch to capture the
         * Authorization header the first time Discord's web app makes any API call.
         * Also falls back to reading localStorage.token for older web builds.
         * Reports the token to the native side via DiscordBridge.onTokenFound().
         */
        private val TOKEN_INTERCEPTOR_JS = """
            (function() {
                if (window.__scDiscordPatched) return;
                window.__scDiscordPatched = true;

                function report(token) {
                    if (!token || token.length < 20) return;
                    // Strip surrounding quotes that localStorage sometimes includes.
                    var t = token.replace(/^"+|"+${'$'}/g, '');
                    if (t.length < 20) return;
                    try { DiscordBridge.onTokenFound(t); } catch(e) {}
                }

                // Intercept XMLHttpRequest.setRequestHeader
                var origSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (name === 'Authorization') report(value);
                    origSetRequestHeader.call(this, name, value);
                };

                // Intercept fetch()
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    if (init && init.headers) {
                        try {
                            var h = init.headers;
                            var auth = (typeof h.get === 'function')
                                ? h.get('Authorization')
                                : (h['Authorization'] || h['authorization']);
                            if (auth) report(auth);
                        } catch(e) {}
                    }
                    return origFetch.apply(this, arguments);
                };

                // Immediate localStorage probe (works on some Discord builds)
                try {
                    var stored = localStorage.getItem('token');
                    if (stored) report(stored);
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
