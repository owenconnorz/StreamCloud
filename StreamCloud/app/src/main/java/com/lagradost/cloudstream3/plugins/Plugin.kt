@file:Suppress("unused")
package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

abstract class Plugin {

    val apis: MutableList<MainAPI> = mutableListOf()

    open fun load(context: Context) {}

    open fun beforeLoad() {}
    open fun afterLoad() {}

    fun registerMainAPI(api: MainAPI) {
        apis.add(api)
    }

    // Parameter must be ExtractorApi (not Any/Object) so the JVM method
    // descriptor is (Lcom/lagradost/cloudstream3/utils/ExtractorApi;)V,
    // matching what plugins compiled against the real CloudStream API expect.
    fun registerExtractorAPI(extractor: ExtractorApi) {}

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
