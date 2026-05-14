package com.aioweb.app.data.ytmusic

import java.security.MessageDigest
import java.util.Locale

/**
 * Generates the signed `Authorization` header YouTube Music's signed-in endpoints
 * expect. Mirrors Metrolist / zionhuang/InnerTube:
 *
 *   1. Pick out the `SAPISID` cookie from our stored browser cookie string.
 *   2. Build `"<unix-ts> <SAPISID> https://music.youtube.com"`.
 *   3. SHA-1 hash → hex digest.
 *   4. Header value = `SAPISIDHASH <unix-ts>_<hex-digest>`.
 *
 * Without this header the endpoint either returns anonymous defaults or 401s —
 * the cookie alone is not enough for personalised data.
 */
internal object YtMusicAuth {

    const val ORIGIN = "https://music.youtube.com"

    /** Extracts a single cookie value from a raw `name1=v1; name2=v2` header string. */
    fun cookieValue(rawCookie: String, name: String): String? {
        return rawCookie.splitToSequence(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Build the `Authorization: SAPISIDHASH ...` value for the given cookie header.
     * Returns `null` when no SAPISID cookie is present (i.e. the user hasn't logged
     * in, or the cookie jar lost it) so callers can fall back to anonymous mode.
     */
    fun sapisidHashHeader(rawCookie: String, originUrl: String = ORIGIN): String? {
        // Prefer the "3P" SAPISID variant that `music.youtube.com` issues to its
        // own origin; fall back to plain SAPISID if the 3P cookie isn't present.
        val sapisid = cookieValue(rawCookie, "__Secure-3PAPISID")
            ?: cookieValue(rawCookie, "SAPISID")
            ?: return null
        val timestamp = System.currentTimeMillis() / 1000L
        val payload = "$timestamp $sapisid $originUrl"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray())
        val hex = buildString(digest.size * 2) {
            for (b in digest) append(String.format(Locale.ROOT, "%02x", b))
        }
        return "SAPISIDHASH ${timestamp}_$hex"
    }
}
