@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

const val PLUGIN_TAG = "PluginInstance"

/**
 * Vendored stub of CloudStream's BasePlugin, kept API-compatible with the
 * published plugin SDK so that compiled .cs3 plugins load correctly.
 *
 * Real CloudStream: Plugin extends BasePlugin (in the library module).
 * Here we mirror that hierarchy so Dalvik can verify the plugin's class
 * without throwing NoClassDefFoundError: BasePlugin.
 *
 * The key contract: [registerMainAPI] adds to [apis], which PluginRuntime
 * reads after [load] returns. Similarly, [registerExtractorAPI] adds to [extractors]
 * for custom video stream extractors.
 *
 * ## Compatibility Notes
 * - This stub is frozen at a specific CloudStream version.
 * - When CloudStream updates its plugin SDK, this file may need updates.
 * - The reflection bridge in PluginRuntime can help mitigate API drift.
 * - If a plugin's API call fails due to missing method, check the error logs
 *   which will indicate the specific incompatibility.
 */
abstract class BasePlugin {

    /**
     * All [MainAPI] providers registered by this plugin during [load].
     * PluginRuntime captures this list immediately after afterLoad() returns.
     */
    val apis: MutableList<MainAPI> = mutableListOf()

    /**
     * All [ExtractorApi] extractors registered by this plugin during [load].
     * PluginRuntime captures this list immediately after afterLoad() returns.
     * Enables plugins to provide custom video stream extractors beyond MainAPI.
     */
    val extractors: MutableList<ExtractorApi> = mutableListOf()

    /**
     * Absolute file path to the .cs3 file.
     * Set by PluginRuntime before beforeLoad() is called.
     */
    var filename: String? = null

    /**
     * Register a [MainAPI] provider.  Must be called from inside [load] /
     * [load(Context)][com.lagradost.cloudstream3.plugins.Plugin.load].
     */
    fun registerMainAPI(element: MainAPI) {
        apis.add(element)
    }

    /**
     * Register an [ExtractorApi] extractor.
     * This enables plugins to provide custom video stream extractors.
     * Must be called from inside [load] / [load(Context)][com.lagradost.cloudstream3.plugins.Plugin.load].
     */
    fun registerExtractorAPI(extractor: ExtractorApi) {
        extractors.add(extractor)
    }

    /** Called before [load]. Override for one-time setup. */
    @Throws(Throwable::class)
    open fun beforeLoad() {}

    /** Called after [load] to signal successful initialisation. */
    @Throws(Throwable::class)
    open fun afterLoad() {}

    /**
     * Cross-platform (KMP) load — invoked when no Android Context is
     * available, or as a fallback from
     * [Plugin.load(Context)][com.lagradost.cloudstream3.plugins.Plugin.load].
     */
    @Throws(Throwable::class)
    open fun load() {}

    /** Called when the plugin is being unloaded / the app is shutting down. */
    @Throws(Throwable::class)
    open fun beforeUnload() {}
}
