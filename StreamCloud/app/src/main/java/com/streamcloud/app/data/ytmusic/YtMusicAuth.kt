package com.streamcloud.app.data.ytmusic

import java.security.MessageDigest
import java.util.Locale

internal object YtMusicAuth {

    const val ORIGIN = "https://music.youtube.com"


    fun cookieValue(rawCookie: String, name: String): String? {
        return rawCookie.splitToSequence(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }


    fun sapisidHashHeader(rawCookie: String, originUrl: String = ORIGIN): String? {


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
