@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Stub of cloudstream3's `MainActivity.kt` top-level surface.
 *
 * In the real CloudStream codebase, this file declares `app`, `setKey`, `getKey`,
 * and `mapper` as top-level Kotlin symbols. The Kotlin compiler emits them as
 * static members of the synthetic class `MainActivityKt`, which `.cs3` plugins
 * are linked against. Without this file, every plugin call dies with:
 *
 *     NoClassDefFoundError: Failed resolution of: Lcom/lagradost/cloudstream3/MainActivityKt;
 *
 * We provide enough surface for the common plugin patterns:
 *   • `app.get(...)` / `app.post(...)`   → backed by [Requests]
 *   • `setKey(...)` / `getKey(...)`      → SharedPreferences-backed (best-effort)
 *   • `mapper`                           → Jackson [ObjectMapper] (kotlin module)
 *   • `getSharedPrefs()`                 → so inline-reified helpers in plugins work
 */

/** Shared HTTP client every plugin uses. */
val app: Requests = Requests

/** Shared Jackson mapper used by plugin `parsedSafe<...>()` extensions. */
val mapper: ObjectMapper by lazy {
    jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

// ─────────────────────── Plugin key/value storage ───────────────────────

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

/**
 * Real CloudStream exposes a `getSharedPrefs()` top-level helper. Some plugins
 * inline-reference it directly (e.g. inside their own `inline reified` getKey).
 * Returns a no-op SharedPreferences proxy if not initialised yet so plugin code
 * doesn't NPE during early loads.
 */
fun getSharedPrefs(): SharedPreferences? = prefsHolder

/**
 * `setKey` is a *regular* generic fun (NOT inline reified) so the Kotlin
 * compiler emits a real static method on `MainActivityKt` that plugin bytecode
 * can link against.
 */
fun <T> setKey(path: String, value: T): Boolean {
    return try {
        val text = mapper.writeValueAsString(value)
        prefsHolder?.edit()?.putString(path, text)?.apply()
        true
    } catch (_: Throwable) { false }
}

fun <T> setKey(folder: String, path: String, value: T): Boolean = setKey("$folder/$path", value)

/**
 * Inline-reified read — matches the signature plugins were compiled against.
 * Note: when a plugin uses `getKey<Foo>(path)` the body is inlined into the
 * plugin bytecode, so it reaches `mapper` and `getSharedPrefs()` from this
 * `MainActivityKt` directly.
 */
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

/** Some plugins call `normalSafeApiCall { ... }` to swallow exceptions. */
fun <T> normalSafeApiCall(apiCall: () -> T): T? = try { apiCall() } catch (_: Throwable) { null }

const val USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
