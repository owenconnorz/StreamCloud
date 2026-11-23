package com.lagradost.cloudstream3.utils

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Small CloudStream URL-encoding compatibility surface used by older extensions.
 */
object StringUtils {
    fun encodeUrl(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
