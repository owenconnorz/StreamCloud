@file:Suppress("unused")
package com.lagradost.api

import android.util.Log as AndroidLog

/**
 * CloudStream logging facade — mirrors com.lagradost.api.Log from the real SDK.
 * The real SDK has NO @JvmStatic — methods are plain instance methods on the
 * singleton, so the JVM treats them as virtual. Plugins call Log.INSTANCE.d(…)
 * (virtual dispatch). Adding @JvmStatic would generate ONLY a static method,
 * removing the virtual dispatch that plugins expect → NoSuchMethodError.
 */
object Log {
    fun d(tag: String, message: String) = AndroidLog.d(tag, message)
    fun i(tag: String, message: String) = AndroidLog.i(tag, message)
    fun w(tag: String, message: String) = AndroidLog.w(tag, message)
    fun e(tag: String, message: String) = AndroidLog.e(tag, message)
    fun v(tag: String, message: String) = AndroidLog.v(tag, message)
    fun wtf(tag: String, message: String) = AndroidLog.wtf(tag, message)

    fun d(tag: String, message: String, t: Throwable?) = AndroidLog.d(tag, message, t)
    fun i(tag: String, message: String, t: Throwable?) = AndroidLog.i(tag, message, t)
    fun w(tag: String, message: String, t: Throwable?) = AndroidLog.w(tag, message, t)
    fun e(tag: String, message: String, t: Throwable?) = AndroidLog.e(tag, message, t)

    // Single-arg convenience
    fun w(tag: String, t: Throwable?) = AndroidLog.w(tag, t)
    fun e(tag: String, t: Throwable?) = AndroidLog.e(tag, "", t)
}
