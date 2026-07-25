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
 * Fetches the signed-in Google account's profile photo URL using the device
 * AccountManager (the same approach used by Metrolist / ViMusic).
 *
 * Flow:
 *  1. Get the first "com.google" account from AccountManager.
 *  2. Try AccountManager.getUserData(account, "picture") — GMS sometimes
 *     caches this without a network round-trip.
 *  3. Fall back to blockingGetAuthToken with the userinfo.profile OAuth2
 *     scope, then call Google's userinfo endpoint to get the "picture" URL.
 *
 * Returns null silently on any failure (no account, no token, network error).
 * The caller should use ytMusicUserAvatar first and only call this as a fallback.
 */
object GoogleAccountHelper {

    private const val SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo"

    suspend fun getPhotoUrl(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val am = AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            val account = accounts.firstOrNull() ?: return@withContext null

            // Fast path: check if GMS already cached the picture URL
            val cached = am.getUserData(account, "picture")
                ?: am.getUserData(account, "photoUrl")
            if (!cached.isNullOrBlank()) return@withContext cached

            // Slower path: get an OAuth2 token and call the userinfo endpoint
            val bundle = am.getAuthToken(
                account,
                SCOPE,
                null,
                false,   // notifyAuthFailure = false (silent — no notification)
                null,    // callback
                null,    // handler
            ).getResult() ?: return@withContext null

            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                ?: return@withContext null

            // Fetch userinfo JSON
            val conn = URL("$USERINFO_URL?access_token=$token")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 5_000
            conn.readTimeout    = 5_000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            JSONObject(json).optString("picture").takeIf { it.isNotBlank() }
        } catch (_: JSONException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
