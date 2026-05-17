@file:Suppress("unused")
package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

/**
 * Plugin base class — every `.cs3` extends this and registers its `MainAPI`(s)
 * by calling `registerMainAPI(...)` from `load()`.
 */
abstract class Plugin {
    /** APIs registered by this plugin (populated by registerMainAPI calls during load). */
    val apis: MutableList<MainAPI> = mutableListOf()

    open fun load(context: Context) {
        // override in plugins; usually does: registerMainAPI(MyProvider())
    }

    open fun beforeLoad() {}
    open fun afterLoad() {}

    fun registerMainAPI(api: MainAPI) {
        apis.add(api)
    }

    /** Many plugins call this to add an extractor — we ignore the extra registry for now. */
    fun registerExtractorAPI(extractor: Any) { /* no-op stub */ }
}

/**
 * `@CloudstreamPlugin` annotation is required on every plugin's main class so that
 * the cloudstream loader can find it. We mirror it here so the plugin's compiled
 * bytecode resolves the symbol.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin
