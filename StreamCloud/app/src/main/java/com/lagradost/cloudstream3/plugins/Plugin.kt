@file:Suppress("unused")
package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

abstract class Plugin {

    val apis: MutableList<MainAPI> = mutableListOf()

    open fun load(context: Context) {

    }

    open fun beforeLoad() {}
    open fun afterLoad() {}

    fun registerMainAPI(api: MainAPI) {
        apis.add(api)
    }


    fun registerExtractorAPI(extractor: Any) {  }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin
