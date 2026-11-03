package com.streamcloud.app.data.spotify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps the YouTube Music match for Spotify tracks so offline status can be shown consistently
 * across Spotify home, search, library, artist, album, and playlist screens.
 */
object SpotifyTrackDownloadMatches {
    private const val PREFS_NAME = "spotify_track_download_matches"
    private const val KEY_PREFIX = "track:"

    private val lock = Any()
    private val mutableMatches = MutableStateFlow<Map<String, String>>(emptyMap())
    private var preferences: SharedPreferences? = null

    fun observe(context: Context): StateFlow<Map<String, String>> {
        prefs(context)
        return mutableMatches.asStateFlow()
    }

    fun remember(context: Context, spotifyTrackId: String?, videoId: String?) {
        val trackId = spotifyTrackId?.trim()?.takeIf(String::isNotEmpty) ?: return
        val matchedVideoId = videoId?.trim()?.takeIf(String::isNotEmpty) ?: return
        val prefs = prefs(context)
        synchronized(lock) {
            if (mutableMatches.value[trackId] == matchedVideoId) return
            prefs.edit().putString(KEY_PREFIX + trackId, matchedVideoId).apply()
            mutableMatches.update { it + (trackId to matchedVideoId) }
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        preferences?.let { return it }
        return synchronized(lock) {
            preferences ?: context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .also { stored ->
                    preferences = stored
                    mutableMatches.value = stored.all.mapNotNull { (key, value) ->
                        if (!key.startsWith(KEY_PREFIX) || value !is String) {
                            null
                        } else {
                            key.removePrefix(KEY_PREFIX) to value
                        }
                    }.toMap()
                }
        }
    }
}
