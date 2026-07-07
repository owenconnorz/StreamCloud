@file:Suppress("unused")
package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

/**
 * The primary Plugin base class for CloudStream-compatible plugins in StreamCloud.
 *
 * Plugins should extend this class and override [load] to register [MainAPI] providers
 * and optionally [ExtractorApi] extractors during plugin initialization.
 *
 * ## Lifecycle
 * 1. Plugin is instantiated (no-arg constructor)
 * 2. [__initContext] is called with the app context
 * 3. [beforeLoad] is called for one-time setup
 * 4. [load] is called with the Android context — register APIs here
 * 5. [afterLoad] is called to finalize initialization
 * 6. PluginRuntime reads [apis] and [extractors]
 *
 * ## Context Delegation
 * The original CloudStream Plugin base exposes context methods directly on the Plugin
 * instance so plugins can call this.getResources(), etc. We delegate these via the
 * stored context.
 *
 * ## API Registration
 * - Call [registerMainAPI] from [load] to register content providers
 * - Call [registerExtractorAPI] from [load] to register video stream extractors
 */
abstract class Plugin {

    val apis: MutableList<MainAPI> = mutableListOf()

    val extractors: MutableList<ExtractorApi> = mutableListOf()

    // ── Context delegation ───────────────────────────────────────────────────
    // The original CloudStream Plugin base exposes context methods directly on
    // the Plugin instance so plugins can call this.getResources(), etc.
    // We store the Context set by PluginRuntime after instantiation and delegate.
    @Volatile private var __pluginContext: Context? = null

    /** Called by PluginRuntime immediately after instantiation, before load(). */
    fun __initContext(ctx: Context) { __pluginContext = ctx }

    fun getResources(): Resources =
        __pluginContext?.resources
            ?: Resources.getSystem()

    fun getSystemService(name: String): Any? =
        __pluginContext?.getSystemService(name)

    fun getPackageName(): String =
        __pluginContext?.packageName ?: ""

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences? =
        __pluginContext?.getSharedPreferences(name, mode)

    fun getString(resId: Int): String =
        runCatching { __pluginContext?.getString(resId) }.getOrNull() ?: ""

    fun getString(resId: Int, vararg formatArgs: Any?): String =
        runCatching { __pluginContext?.getString(resId, *formatArgs) }.getOrNull() ?: ""

    // ── Lifecycle ────────────────────────────────────────────────────────────

    open fun load(context: Context) {}

    open fun beforeLoad() {}
    open fun afterLoad() {}

    fun registerMainAPI(api: MainAPI) {
        apis.add(api)
    }

    /**
     * Register an [ExtractorApi] extractor.
     * This enables plugins to provide custom video stream extractors.
     *
     * Parameter must be ExtractorApi (not Any/Object) so the JVM method
     * descriptor is (Lcom/lagradost/cloudstream3/utils/ExtractorApi;)V,
     * allowing proper method resolution across classloaders.
     *
     * ## Example
     * ```kotlin
     * class MyPlugin : Plugin() {
     *     override fun load(context: Context) {
     *         registerMainAPI(MyProvider())
     *         registerExtractorAPI(MyCustomExtractor())
     *     }
     * }
     * ```
     */
    fun registerExtractorAPI(extractor: ExtractorApi) {
        extractors.add(extractor)
    }

    // ── Settings callbacks — added in later CloudStream API versions ────────
    // Plugins call setOpenSettings(handler) to register a settings-open callback.
    // We keep a reference so the host can invoke it, but it's optional.
    var openSettings: ((Context) -> Unit)? = null
        private set

    @Suppress("UNCHECKED_CAST")
    fun setOpenSettings(openSettings: ((Context) -> Unit)?) {
        this.openSettings = openSettings
    }

    // Overload matching the JVM descriptor plugins compiled against:
    //   setOpenSettings(Lkotlin/jvm/functions/Function1;)V
    // Kotlin already generates this via the typed overload above, but an
    // explicit Any? overload avoids NoSuchMethodError on plugins that use an
    // unparameterised Function1 reference at the call site.
    fun setOpenSettings(openSettings: Any?) {
        @Suppress("UNCHECKED_CAST")
        this.openSettings = openSettings as? ((Context) -> Unit)
    }

    // ── Misc stubs for API surface parity ───────────────────────────────────
    // These no-op stubs prevent NoSuchMethodError when plugins compiled against
    // a newer CloudStream API call methods that aren't present in our build.

    /** Called by some plugins to request a runtime permission. No-op stub. */
    open fun requestPermission(context: Context, permission: String) {}

    /** Called by some plugins with their version info. No-op stub. */
    open fun onPluginLoaded(pluginName: String, version: Int) {}
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin
