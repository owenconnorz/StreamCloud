package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity

/**
 * Binary-compatibility shim for plugins that reference CloudStream's host activity.
 *
 * StreamCloud's real activity lives in a different package and deliberately does not
 * extend this class. Legacy plugins can resolve their `MainActivity` type checks
 * without mistaking StreamCloud's activity for CloudStream's or crashing on resume.
 * This shim is never declared in the manifest or launched by StreamCloud.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        /**
         * StreamPlay signals this after registering providers. Plugin registration is
         * managed directly by StreamCloud, so the compatibility event is intentionally
         * a no-op.
         */
        val afterPluginsLoadedEvent: (Boolean) -> Unit = {}
    }
}
