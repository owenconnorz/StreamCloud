package com.streamcloud.app.data.util

import android.accounts.AccountManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the signed-in Google account's profile photo URL.
 *
 * Primary strategy (Metrolist / InnerTune approach):
 *   Call the YouTube Music `account/account_menu` API using the SAPISID cookie
 *   that was stored during the YouTube Music WebView login.  This is the same
 *   approach used by Metrolist — no separate Google Sign-In SDK required.
 *
 * Fallback strategy:
 *   Use Android's AccountManager to find the first "com.google" account and try
 *   to obtain an OAuth2 token for the userinfo.profile scope.
 */
object GoogleAccountHelper {

    // ── YouTube Music API (primary — Metrolist approach) ─────────────────────

    private const val YTM_API_URL =
        "https://music.youtube.com/youtubei/v1/account/account_menu" +
        "?prettyPrint=false"

    private const val YTM_BODY = """{"context":{"client":{"hl":"en","gl":"US",""" +
        """"clientName":"WEB_REMIX","clientVersion":"1.20240101.01.00"}}}"""

    /** Profile photos from Google always contain /a/ in the path. */
    private val PROFILE_PHOTO_RE = Regex("""https://lh3\.googleusercontent\.com/a/[^"\\]+""")

    /**
     * Calls the YouTube Music account menu endpoint with the stored SAPISID
     * cookie and extracts the signed-in user's profile photo URL.
     *
     * Returns null on any failure (network error, not signed in, bad JSON).
     */
    suspend fun fetchFromYtMusicApi(cookie: String): String? = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) return@withContext null
        try {
            val conn = URL(YTM_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.setRequestProperty("X-Goog-AuthUser", "0")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36",
            )
            conn.outputStream.use { it.write(YTM_BODY.toByteArray(Charsets.UTF_8)) }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Find the first profile photo URL in the response
            // Profile photo URLs always contain /a/ (user account photos)
            PROFILE_PHOTO_RE.find(body)?.value
                ?.replace(Regex("=s\\d+.*$"), "=s256") // bump to 256 px
        } catch (_: Exception) {
            null
        }
    }

    // ── AccountManager fallback (device Google account) ───────────────────────

    private const val SCOPE       = "oauth2:https://www.googleapis.com/auth/userinfo.profile"
    private const val USERINFO    = "https://www.googleapis.com/oauth2/v1/userinfo"

    suspend fun getPhotoUrl(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val am      = AccountManager.get(context)
            val account = am.getAccountsByType("com.google").firstOrNull()
                ?: return@withContext null

            // Fast path — GMS sometimes caches this
            val cached = am.getUserData(account, "picture")
                ?: am.getUserData(account, "photoUrl")
            if (!cached.isNullOrBlank()) return@withContext cached

            // Network path — get OAuth2 token then call userinfo
            val bundle = am.getAuthToken(account, SCOPE, null, false, null, null).getResult()
                ?: return@withContext null
            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                ?: return@withContext null

            val conn = URL("$USERINFO?access_token=$token")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 5_000
            conn.readTimeout    = 5_000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            JSONObject(json).optString("picture").takeIf { it.isNotBlank() }
        } catch (_: JSONException) { null
        } catch (_: Exception)    { null }
    }
}
