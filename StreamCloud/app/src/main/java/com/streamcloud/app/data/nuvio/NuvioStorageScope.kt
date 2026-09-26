package com.streamcloud.app.data.nuvio

import android.content.Context
import java.security.MessageDigest

/**
 * Identifies the local copy of a Nuvio account profile. Account IDs are hashed
 * before they are used in filenames or preference keys.
 */
data class NuvioStorageScope(
    val userId: String,
    val localProfileId: String,
) {
    val storageKey: String
        get() = stableKey("$userId\u0000$localProfileId")
}

internal fun stableKey(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Synchronous mirror of the current Nuvio user ID for code that must select a
 * Room database before it can suspend. Tokens remain in DataStore only.
 */
object NuvioAccountScopeStore {
    private const val PREFS = "nuvio_account_scope"
    private const val USER_ID = "user_id"

    fun currentUserId(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(USER_ID, "")
            .orEmpty()
            .trim()

    fun setCurrentUserId(context: Context, userId: String) {
        check(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(USER_ID, userId.trim())
                .commit(),
        ) { "Could not persist the current Nuvio account scope" }
    }
}