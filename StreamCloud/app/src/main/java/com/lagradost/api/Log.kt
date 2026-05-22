@file:Suppress("unused")
package com.lagradost.api

import android.util.Log as AndroidLog

/**
 * CloudStream logging facade — mirrors the real com.lagradost.api.Log interface
 * (declared as `expect object` in the multiplatform library).
 *
 * Plugins compiled against CloudStream call com.lagradost.api.Log.d/i/w/e.
 * Without this class, loading any plugin that imports it throws
 * NoClassDefFoundError: Failed resolution of: Lcom/lagradost/api/Log;
 */
object Log {
    @JvmStatic fun d(tag: String, message: String) = AndroidLog.d(tag, message)
    @JvmStatic fun i(tag: String, message: String) = AndroidLog.i(tag, message)
    @JvmStatic fun w(tag: String, message: String) = AndroidLog.w(tag, message)
    @JvmStatic fun e(tag: String, message: String) = AndroidLog.e(tag, message)
    @JvmStatic fun v(tag: String, message: String) = AndroidLog.v(tag, message)
    @JvmStatic fun wtf(tag: String, message: String) = AndroidLog.wtf(tag, message)

    // Throwable overloads used by some plugins
    @JvmStatic fun d(tag: String, message: String, t: Throwable?) = AndroidLog.d(tag, message, t)
    @JvmStatic fun i(tag: String, message: String, t: Throwable?) = AndroidLog.i(tag, message, t)
    @JvmStatic fun w(tag: String, message: String, t: Throwable?) = AndroidLog.w(tag, message, t)
    @JvmStatic fun e(tag: String, message: String, t: Throwable?) = AndroidLog.e(tag, message, t)
}
