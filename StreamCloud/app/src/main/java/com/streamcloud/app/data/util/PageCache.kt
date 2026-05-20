package com.streamcloud.app.data.util

import android.content.Context

/**
 * Lightweight disk-backed JSON snapshot cache for home-page data.
 * Backed by SharedPreferences — no Room dependency, survives process death.
 *
 * Each entry stores:
 *   prefs[key]      = json string
 *   prefs[key_ts]   = write timestamp (Long)
 *
 * Pattern: show stale data immediately on launch, refresh in background,
 * update UI when fresh data arrives (stale-while-revalidate).
 */
object PageCache {
    private const val PREFS_NAME = "streamcloud_page_cache"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist a JSON string under [key]. Overwrites any existing entry. */
    fun put(ctx: Context, key: String, json: String) {
        prefs(ctx).edit()
            .putString(key, json)
            .putLong("${key}_ts", System.currentTimeMillis())
            .apply()
    }

    /**
     * Return the cached JSON if it is fresher than [ttlMs] milliseconds,
     * otherwise null (triggers a network refresh).
     */
    fun getFresh(ctx: Context, key: String, ttlMs: Long): String? {
        val p = prefs(ctx)
        val ts = p.getLong("${key}_ts", 0L)
        if (System.currentTimeMillis() - ts > ttlMs) return null
        return p.getString(key, null)
    }

    /**
     * Return the cached JSON regardless of age — used to pre-populate the
     * UI while a background refresh is in-flight.
     */
    fun getStale(ctx: Context, key: String): String? =
        prefs(ctx).getString(key, null)

    fun clear(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key).remove("${key}_ts").apply()
    }

    // ─── Well-known cache keys ────────────────────────────────────────────────
    const val KEY_TMDB_COLLECTIONS = "tmdb_collections"
    const val KEY_STREMIO_ROWS     = "stremio_rows"

    /** How long TMDB home data is considered fresh (1 hour). */
    const val TTL_TMDB_MS = 60 * 60 * 1000L
    /** How long Stremio catalog rows are considered fresh (30 min). */
    const val TTL_STREMIO_MS = 30 * 60 * 1000L
}
