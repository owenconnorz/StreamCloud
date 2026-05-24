@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

import android.content.Context
import android.content.SharedPreferences
val app: com.lagradost.nicehttp.Requests = com.lagradost.nicehttp.Requests

// mapper is declared in MainAPI.kt as val mapper: JsonMapper (same package).
// JsonMapper extends ObjectMapper so writeValueAsString/readValue work unchanged.

@Volatile private var prefsHolder: SharedPreferences? = null

internal fun installPrefs(context: Context) {
    if (prefsHolder == null) {
        synchronized(Requests) {
            if (prefsHolder == null) {
                prefsHolder = context.applicationContext
                    .getSharedPreferences("plugins_storage", Context.MODE_PRIVATE)
            }
        }
    }
}

fun getSharedPrefs(): SharedPreferences? = prefsHolder

fun <T> setKey(path: String, value: T): Boolean {
    return try {
        val text = mapper.writeValueAsString(value)
        prefsHolder?.edit()?.putString(path, text)?.apply()
        true
    } catch (_: Throwable) { false }
}

fun <T> setKey(folder: String, path: String, value: T): Boolean = setKey("$folder/$path", value)

inline fun <reified T : Any> getKey(path: String): T? {
    return try {
        val raw = getSharedPrefs()?.getString(path, null) ?: return null
        mapper.readValue(raw, T::class.java)
    } catch (_: Throwable) { null }
}

inline fun <reified T : Any> getKey(folder: String, path: String): T? = getKey<T>("$folder/$path")
inline fun <reified T : Any> getKey(path: String, default: T): T = getKey<T>(path) ?: default

fun removeKey(path: String) { prefsHolder?.edit()?.remove(path)?.apply() }
fun removeKey(folder: String, path: String) = removeKey("$folder/$path")
fun removeKeys(folder: String) {
    val p = prefsHolder ?: return
    p.all.keys.filter { it.startsWith("$folder/") }.forEach { p.edit().remove(it).apply() }
}

fun <T> normalSafeApiCall(apiCall: () -> T): T? = try { apiCall() } catch (_: Throwable) { null }

const val USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
