package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Binary-compatibility subset used by CloudStream extensions that read the host preferences
 * directly. Keep the preference file name and key format aligned with CloudStream.
 */
object DataStore {
    private const val PREFERENCES_NAME = "rebuild_preference"

    fun getSharedPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"
}
