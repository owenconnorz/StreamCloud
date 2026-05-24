package com.lagradost.api

import android.util.Log as AndroidLog

/**
 * Stub for com.lagradost.api.Log used by Score and other classes.
 * Delegates to Android's standard Log.
 */
object Log {
    fun w(tag: String, msg: String)    = AndroidLog.w(tag, msg)
    fun e(tag: String, msg: String)    = AndroidLog.e(tag, msg)
    fun d(tag: String, msg: String)    = AndroidLog.d(tag, msg)
    fun i(tag: String, msg: String)    = AndroidLog.i(tag, msg)
    fun v(tag: String, msg: String)    = AndroidLog.v(tag, msg)
    fun wtf(tag: String, msg: String)  = AndroidLog.wtf(tag, msg)
}
