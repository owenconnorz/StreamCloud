@file:Suppress("unused")
package com.lagradost.cloudstream3.plugins

/**
 * Marks a [Plugin] subclass as the entry point for a .cs3 plugin.
 * Must appear on exactly one class per plugin JAR/DEX file.
 *
 * This is a runtime-retained annotation so that [PluginRuntime] can discover
 * the entry-point class via reflection (see scanDexForPluginClass).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin
