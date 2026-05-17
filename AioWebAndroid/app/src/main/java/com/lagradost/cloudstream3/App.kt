@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

/**
 * Type-aliases so that all existing `com.lagradost.cloudstream3.Requests` /
 * `com.lagradost.cloudstream3.NiceResponse` references continue to compile,
 * while the actual implementation lives in `com.lagradost.nicehttp` —
 * which is where `.cs3` plugins expect to find it.
 *
 * The critical thing: `MainActivityKt.getApp()` MUST have the JVM descriptor
 *   getApp()Lcom/lagradost/nicehttp/Requests;
 * A typealias is erased at the JVM level, so the `val app` property in
 * MainActivity.kt is declared as `com.lagradost.nicehttp.Requests` directly.
 */
typealias Requests    = com.lagradost.nicehttp.Requests
typealias NiceResponse = com.lagradost.nicehttp.NiceResponse
