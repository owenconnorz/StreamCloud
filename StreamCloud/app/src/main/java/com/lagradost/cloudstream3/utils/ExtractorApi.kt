@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ExtractorLink

// Global list of registered extractor APIs — BasePlugin.registerExtractorAPI adds here
val extractorApis: MutableList<ExtractorApi> = mutableListOf()

/**
 * Base class for all CloudStream extractor implementations.
 * Plugins that bundle a custom extractor (e.g. PornobaeExtractor) extend this.
 * Without this stub, any plugin that defines an extractor throws
 * NoClassDefFoundError: Failed resolution of: Lcom/lagradost/cloudstream3/utils/ExtractorApi;
 * which prevents the Plugin class itself from loading in scanDexForPluginClass.
 */
abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean

    open var sourcePlugin: String? = null

    @Throws(Exception::class)
    abstract suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>?

    open suspend fun getSafeUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit = {}
    ) {
        getUrl(url, referer)?.forEach(callback)
    }

    open fun getExtractorUrl(id: String): String = id

    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        getUrl(url, referer)?.forEach(callback)
        return true
    }
}

// Utility: register an extractor and immediately use it
suspend fun invokeExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit = {},
    callback: (ExtractorLink) -> Unit = {}
) {
    extractorApis.forEach { api ->
        if (url.startsWith(api.mainUrl)) {
            runCatching { api.getSafeUrl(url, referer, subtitleCallback, callback) }
        }
    }
}

// Short-link resolver stub (some plugins import this)
object ShortLink {
    suspend fun resolve(url: String): String = url
}

// AppUtils stub for JSON parsing — plugins import these
object AppUtils {
    fun Any.toJson(): String = this.toString()

    inline fun <reified T> tryParseJson(value: String?): T? = null
    inline fun <reified T> parseJson(value: String): T {
        throw UnsupportedOperationException("parseJson stub not implemented")
    }
}
