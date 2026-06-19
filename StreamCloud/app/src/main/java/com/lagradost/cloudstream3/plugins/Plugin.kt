@file:Suppress("unused")
package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

abstract class Plugin {

    val apis: MutableList<MainAPI> = mutableListOf()

    open fun load(context: Context) {

    }

    open fun beforeLoad() {}
    open fun afterLoad() {}

    fun registerMainAPI(api: MainAPI) {
        apis.add(api)
    }

    // Parameter must be ExtractorApi (not Any/Object) so the JVM method
    // descriptor is (Lcom/lagradost/cloudstream3/utils/ExtractorApi;)V,
    // matching what plugins compiled against the real CloudStream API expect.
    fun registerExtractorAPI(extractor: ExtractorApi) { }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin
