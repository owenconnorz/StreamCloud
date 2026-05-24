package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import java.io.Reader

object AppUtils {
    fun Any.toJson(): String {
        if (this is String) return this
        return mapper.writeValueAsString(this)
    }

    inline fun <reified T> parseJson(value: String): T = mapper.readValue(value)

    inline fun <reified T> parseJson(reader: Reader, valueType: Class<T>): T = mapper.readValue(reader, valueType)

    inline fun <reified T> tryParseJson(value: String?): T? {
        if (value == null) return null
        return try { parseJson(value) } catch (_: Exception) { null }
    }
}
