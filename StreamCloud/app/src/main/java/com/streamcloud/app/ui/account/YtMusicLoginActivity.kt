package com.streamcloud.app.ui.account

import android.annotation.SuppressLint
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
import kotlinx.coroutines.launch

class YtMusicLoginActivity : ComponentActivity() {

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
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        container.addView(progress)
        container.addView(web)
        setContentView(container)


        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url == null) return


                if (!url.contains("music.youtube.com")) return
                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com")
                    .orEmpty()
                if (cookie.isBlank() || !cookie.contains("SAPISID")) return






                view?.evaluateJavascript(
                    """
                    (function () {
                      try {
                        // Strategy 1 — yt.config_ LOGGED_IN_USER_DATA (most reliable;
                        // this JS object is always present when signed in and is not
                        // affected by Polymer/LitElement component render timing).
                        var cfg = (window.yt && window.yt.config_ && window.yt.config_['LOGGED_IN_USER_DATA']) || null;
                        if (cfg && cfg['photoUrl']) {
                          return JSON.stringify({ name: cfg['name'] || '', avatar: cfg['photoUrl'] || '' });
                        }
                        // Strategy 2 — ytcfg global (older YouTube Music builds).
                        var ytcfgData = (window.ytcfg && window.ytcfg.data_ && window.ytcfg.data_['LOGGED_IN_USER_DATA']) || null;
                        if (ytcfgData && ytcfgData['photoUrl']) {
                          return JSON.stringify({ name: ytcfgData['name'] || '', avatar: ytcfgData['photoUrl'] || '' });
                        }
                        // Strategy 3 — DOM scraping fallback (breaks if YouTube changes
                        // their Polymer component names, which is why this is last).
                        var btn = document.querySelector('#avatar-btn, ytmusic-settings-button, [data-testid="account-photo"]');
                        var name = btn ? (btn.getAttribute('aria-label') || '').trim() : '';
                        var img = document.querySelector('#avatar-btn img, ytmusic-settings-button img, yt-img-shadow img, [data-testid="account-photo"] img');
                        var avatar = img ? (img.getAttribute('src') || img.getAttribute('data-src') || '').trim() : '';
                        return JSON.stringify({ name: name, avatar: avatar });
                      } catch (e) { return JSON.stringify({ name: '', avatar: '' }); }
                    })();
                    """.trimIndent(),
                ) { raw ->
                    val (n, a) = parseUserJson(raw.orEmpty())
                    lifecycleScope.launch {
                        val sl = ServiceLocator.get(applicationContext)
                        sl.settings.setYtMusicCookie(cookie)
                        sl.settings.setYtMusicUser(
                            name = n.ifBlank { "Signed in" },
                            avatar = a,
                        )
                        com.streamcloud.app.data.newpipe.NewPipeDownloader.instance.ytMusicCookie = cookie
                        finish()
                    }
                }
            }
        }



        web.loadUrl(
            "https://accounts.google.com/ServiceLogin" +
                "?ltmpl=music&service=youtube&passive=true&hl=en&continue=" +
                "https%3A%2F%2Fmusic.youtube.com%2F",
        )
    }


    private fun parseUserJson(rawWrapped: String): Pair<String, String> {
        if (rawWrapped.isBlank() || rawWrapped == "null") return "" to ""




        val nameMatch = Regex("\\\\?\"name\\\\?\":\\\\?\"([^\"\\\\]+)\\\\?\"").find(rawWrapped)
        val avatarMatch = Regex("\\\\?\"avatar\\\\?\":\\\\?\"([^\"\\\\]+)\\\\?\"").find(rawWrapped)
        val name = nameMatch?.groupValues?.getOrNull(1).orEmpty()


            .substringAfterLast('—').trim()
            .ifBlank { nameMatch?.groupValues?.getOrNull(1).orEmpty().trim() }




        val avatar = avatarMatch?.groupValues?.getOrNull(1).orEmpty()
            .replace(Regex("=s\\d+.*$"), "=s256")
        return name to avatar
    }
}
