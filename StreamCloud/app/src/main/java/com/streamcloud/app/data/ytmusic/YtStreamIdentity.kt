package com.streamcloud.app.data.ytmusic

import java.security.MessageDigest

internal object YtStreamIdentity {
    fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}