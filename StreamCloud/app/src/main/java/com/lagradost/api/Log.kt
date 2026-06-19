package com.lagradost.api

import android.util.Log as AndroidLog

object Log {
    // Intentionally NOT annotated with @JvmStatic.
    //
    // Plugins compiled against the real CloudStream API call these methods via
    // `invokevirtual` (i.e. as instance methods on Log.INSTANCE).  Adding
    // @JvmStatic moves them to purely static JVM methods, which causes an
    // IncompatibleClassChangeError at runtime when a plugin tries to call them
    // as virtual methods.  Without @JvmStatic, Kotlin generates normal instance
    // methods on the singleton class — exactly what plugins expect.
    //
    // Kotlin call-sites (e.g. `Log.d(tag, msg)`) compile to
    // `Log.INSTANCE.d(tag, msg)` and work correctly either way.

    // Single-String overloads
    fun w(tag: String, msg: String) { AndroidLog.w(tag, msg) }
    fun e(tag: String, msg: String) { AndroidLog.e(tag, msg) }
    fun d(tag: String, msg: String) { AndroidLog.d(tag, msg) }
    fun i(tag: String, msg: String) { AndroidLog.i(tag, msg) }
    fun v(tag: String, msg: String) { AndroidLog.v(tag, msg) }
    fun wtf(tag: String, msg: String) { AndroidLog.wtf(tag, msg) }

    // Throwable overloads
    fun w(tag: String, msg: String, tr: Throwable?) { if (tr != null) AndroidLog.w(tag, msg, tr) else AndroidLog.w(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?) { if (tr != null) AndroidLog.e(tag, msg, tr) else AndroidLog.e(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable?) { if (tr != null) AndroidLog.d(tag, msg, tr) else AndroidLog.d(tag, msg) }
    fun i(tag: String, msg: String, tr: Throwable?) { if (tr != null) AndroidLog.i(tag, msg, tr) else AndroidLog.i(tag, msg) }
    fun v(tag: String, msg: String, tr: Throwable?) { if (tr != null) AndroidLog.v(tag, msg, tr) else AndroidLog.v(tag, msg) }
    fun wtf(tag: String, msg: String, tr: Throwable?) { if (tr != null) AndroidLog.wtf(tag, msg, tr) else AndroidLog.wtf(tag, msg) }
}
