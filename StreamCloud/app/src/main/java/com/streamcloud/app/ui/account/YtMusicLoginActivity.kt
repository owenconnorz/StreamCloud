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

/**
 * WebView-based YouTube Music login. Mirrors Metrolist's flow:
 *
 *   1. Send the user to `accounts.google.com/ServiceLogin?service=youtube` with the next
 *      param pointing at music.youtube.com.
 *   2. After successful login Google redirects back to music.youtube.com with cookies set.
 *   3. We pull the resulting `Cookie:` header out of [CookieManager] and persist it via
 *      [SettingsRepository.setYtMusicCookie] — that single string is what authenticates
 *      every subsequent NewPipe request.
 *
 * The Activity finishes itself the moment we see a logged-in `music.youtube.com` page,
 * which is detected by the presence of the `SAPISID` cookie.
 */
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

        // Drop any stale cookies before sign-in so we don't carry a half-expired session.
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
                // We only consider the user "logged in" once they reach a music.youtube.com
                // page AND the cookie jar has the SAPISID cookie (Google's signed-in marker).
                if (!url.contains("music.youtube.com")) return
                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com")
                    .orEmpty()
                if (cookie.isBlank() || !cookie.contains("SAPISID")) return
                // Best-effort: scrape the user's display name + avatar from the
                // music.youtube.com page. The signed-in topbar exposes the user
                // avatar inside an <img id="img" src="…googleusercontent.com…">
                // and the display name as the `aria-label` on the avatar
                // button. We pull them out via a single JS evaluate and persist
                // whatever we find — failures fall back to "Signed in" / "".
                view?.evaluateJavascript(
                    """
                    (function () {
                      try {
                        var btn = document.querySelector('button[aria-label][id="avatar-btn"], #avatar-btn button, ytmusic-pivot-bar-renderer ytmusic-pivot-bar-item-renderer[tab-id="FEmusic_account"]');
                        var name = btn ? (btn.getAttribute('aria-label') || '').trim() : '';
                        var img = document.querySelector('#avatar-btn img, ytmusic-settings-button img, img.ytmusic-settings-button');
                        var avatar = img ? (img.getAttribute('src') || '').trim() : '';
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

        // Send users straight at the YT Music login URL — Google's login page detects
        // the embedded `next=` and redirects back automatically once they finish.
        web.loadUrl(
            "https://accounts.google.com/ServiceLogin" +
                "?ltmpl=music&service=youtube&passive=true&hl=en&continue=" +
                "https%3A%2F%2Fmusic.youtube.com%2F",
        )
    }

    /**
     * Parse the `JSON.stringify({name, avatar})` payload returned by
     * [WebView.evaluateJavascript]. WebView wraps the inner string in another
     * pair of quotes, so we strip the outer wrapper before extracting fields.
     */
    private fun parseUserJson(rawWrapped: String): Pair<String, String> {
        if (rawWrapped.isBlank() || rawWrapped == "null") return "" to ""
        // evaluateJavascript returns a JSON-encoded value, e.g.
        //   "\"{\\\"name\\\":\\\"Foo\\\",\\\"avatar\\\":\\\"https://…\\\"}\""
        // We want the inner JSON object. Easiest path: regex out name/avatar
        // values directly without lugging in a JSON parser for two fields.
        val nameMatch = Regex("\\\\?\"name\\\\?\":\\\\?\"([^\"\\\\]+)\\\\?\"").find(rawWrapped)
        val avatarMatch = Regex("\\\\?\"avatar\\\\?\":\\\\?\"([^\"\\\\]+)\\\\?\"").find(rawWrapped)
        val name = nameMatch?.groupValues?.getOrNull(1).orEmpty()
            // The aria-label is usually "Account menu — Foo Bar" or just the
            // user's name. Strip the prefix when present.
            .substringAfterLast('—').trim()
            .ifBlank { nameMatch?.groupValues?.getOrNull(1).orEmpty().trim() }
        // Bump the avatar to s256 (default is s48 / s64) so the in-app round
        // button doesn't pixelate on hidpi displays.
        val avatar = avatarMatch?.groupValues?.getOrNull(1).orEmpty()
            .replace(Regex("=s\\d+(-c)?"), "=s256")
        return name to avatar
    }
}
