package com.lagradost.api

import android.util.Log as AndroidLog

object Log {
    @JvmStatic fun w(tag: String, msg: String)   = AndroidLog.w(tag, msg)
    @JvmStatic fun e(tag: String, msg: String)   = AndroidLog.e(tag, msg)
    @JvmStatic fun d(tag: String, msg: String)   = AndroidLog.d(tag, msg)
    @JvmStatic fun i(tag: String, msg: String)   = AndroidLog.i(tag, msg)
    @JvmStatic fun v(tag: String, msg: String)   = AndroidLog.v(tag, msg)
    @JvmStatic fun wtf(tag: String, msg: String) = AndroidLog.wtf(tag, msg)

    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?)   = if (tr != null) AndroidLog.w(tag, msg, tr) else AndroidLog.w(tag, msg)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?)   = if (tr != null) AndroidLog.e(tag, msg, tr) else AndroidLog.e(tag, msg)
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable?)   = if (tr != null) AndroidLog.d(tag, msg, tr) else AndroidLog.d(tag, msg)
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable?)   = if (tr != null) AndroidLog.i(tag, msg, tr) else AndroidLog.i(tag, msg)
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable?)   = if (tr != null) AndroidLog.v(tag, msg, tr) else AndroidLog.v(tag, msg)
}
