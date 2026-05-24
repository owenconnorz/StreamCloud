@file:Suppress("NOTHING_TO_INLINE", "unused")
package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import java.io.Reader

object AppUtils {

    /** Serialize any object to its JSON representation.
     *  If the receiver is already a String it is returned as-is.
     *  Mirrors the real CloudStream AppUtils.toJson extension so that plugin
     *  calls compiled as AppUtils.INSTANCE.toJson(obj) resolve correctly. */
    fun Any.toJson(): String {
        if (this is String) return this
        return mapper.writeValueAsString(this)
    }

    /** Deserialize a JSON string to the reified type T using the shared mapper. */
    inline fun <reified T> parseJson(value: String): T {
        return mapper.readValue(value)
    }

    /** Deserialize from a Reader. */
    inline fun <reified T> parseJson(reader: Reader, valueType: Class<T>): T {
        return mapper.readValue(reader, valueType)
    }

    /** Like parseJson but returns null on any parse failure instead of throwing. */
    inline fun <reified T> tryParseJson(value: String?): T? {
        return try {
            parseJson(value ?: return null)
        } catch (_: Exception) {
            null
        }
    }
}
