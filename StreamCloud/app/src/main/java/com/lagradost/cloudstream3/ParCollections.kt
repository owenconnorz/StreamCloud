package com.lagradost.cloudstream3

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/*
 * CloudStream plugin compatibility helpers.
 *
 * IMPORTANT: we provide BOTH Iterable<A> and List<A> receiver overloads because
 * the two generate different JVM static method descriptors:
 *   amap(Ljava/lang/Iterable; ...)   – generic iterable call sites
 *   amap(Ljava/util/List; ...)       – many CloudStream plugins
 *   amapIndexed(Ljava/util/List; ...) – used by providers that need index-aware mapping
 *
 * Without the List overloads plugins may crash with:
 *   NoSuchMethodError: No static method amap(Ljava/util/List; ...)
 * or
 *   NoSuchMethodError: No static method amapIndexed(Ljava/util/List; ...)
 */

// ── Iterable receivers ──────────────────────────────────────────────────────

suspend fun <T> runAllAsync(vararg jobs: suspend () -> T): List<T> =
    coroutineScope { jobs.map { async { it() } }.awaitAll() }

suspend fun <A, B> Iterable<A>.amap(transform: suspend (A) -> B): List<B> =
    coroutineScope { map { async { transform(it) } }.awaitAll() }

suspend fun <A, B> Iterable<A>.apmap(transform: suspend (A) -> B): List<B> =
    amap(transform)

suspend fun <A, B> Iterable<A>.amapNotNull(transform: suspend (A) -> B?): List<B> =
    coroutineScope {
        map { async { runCatching { transform(it) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
suspend fun <A> Iterable<A>.forEach(block: suspend (A) -> Unit) {
    coroutineScope { map { async { runCatching { block(it) } } }.awaitAll() }
}

suspend fun <T> runAllAsyncNotNull(vararg jobs: suspend () -> T?): List<T> =
    coroutineScope {
        jobs.map { async { runCatching { it() }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

suspend fun <A, B> Iterable<A>.amapIndexed(transform: suspend (Int, A) -> B): List<B> =
    coroutineScope {
        mapIndexed { index, value -> async { transform(index, value) } }.awaitAll()
    }

// ── List receivers (explicit) ───────────────────────────────────────────────
// Keep JVM names as-is (no @JvmName) so plugins resolve exact CloudStream signatures.

suspend fun <A, B> List<A>.amap(transform: suspend (A) -> B): List<B> =
    (this as Iterable<A>).amap(transform)

suspend fun <A, B> List<A>.apmap(transform: suspend (A) -> B): List<B> =
    (this as Iterable<A>).apmap(transform)

suspend fun <A, B> List<A>.amapNotNull(transform: suspend (A) -> B?): List<B> =
    (this as Iterable<A>).amapNotNull(transform)

suspend fun <A, B> List<A>.amapIndexed(transform: suspend (Int, A) -> B): List<B> =
    coroutineScope {
        mapIndexed { index, value -> async { transform(index, value) } }.awaitAll()
    }
