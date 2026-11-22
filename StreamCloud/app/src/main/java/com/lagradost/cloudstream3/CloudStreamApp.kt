package com.lagradost.cloudstream3

import android.content.Context

/**
 * Small binary-compatibility surface for extensions that expect CloudStream's application
 * singleton. StreamCloud has its own Application class, so this class is not registered in the
 * manifest; it only exposes the plugin APIs needed at runtime.
 */
class CloudStreamApp {
    companion object {
        @Volatile
        private var appContext: Context? = null

        val context: Context?
            get() = appContext

        internal fun installContext(context: Context) {
            val applicationContext = context.applicationContext
            appContext = applicationContext
            installPrefs(applicationContext)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            val currentContext = appContext ?: return
            installPrefs(currentContext)
            com.lagradost.cloudstream3.setKey(folder, path, value)
        }
    }
}
