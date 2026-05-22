@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

// Annotation used by the plugin toolchain to identify the entry-point class
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

/**
 * Android-specific plugin base.  Extends [BasePlugin] so that the class
 * hierarchy matches compiled .cs3 plugins: MyPlugin → Plugin → BasePlugin.
 *
 * [apis] and [registerMainAPI] are inherited from [BasePlugin].
 */
abstract class Plugin : BasePlugin() {

    /**
     * Called by PluginRuntime when your plugin is loaded on Android.
     * Call [registerMainAPI] here to make your provider available.
     *
     * Default implementation falls through to the KMP [BasePlugin.load].
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    /**
     * Android resources for plugins that set requiresResources = true in
     * their build.gradle.  Set by PluginRuntime when resources are available.
     */
    var resources: Resources? = null

    /**
     * Optional in-app settings screen callback.
     */
    var openSettings: ((context: Context) -> Unit)? = null
}
