package com.lagradost.cloudstream3

import kotlinx.coroutines.*

/**
 * Stub of CloudStream's ParCollections — provides the parallel-execution helpers
 * that plugins call at runtime.  The JVM class is ParCollectionsKt so all
 * top-level functions here are accessible as static methods on that class.
 */

/** Run all jobs concurrently and return their results in order. */
suspend fun <T> runAllAsync(vararg jobs: suspend () -> T): List<T> =
    coroutineScope { jobs.map { async { it() } }.awaitAll() }

/** Parallel map — runs [transform] on every element concurrently. */
suspend fun <A, B> Iterable<A>.amap(transform: suspend (A) -> B): List<B> =
    coroutineScope { map { async { transform(it) } }.awaitAll() }

/** Parallel map alias used by some plugins. */
suspend fun <A, B> Iterable<A>.apmap(transform: suspend (A) -> B): List<B> =
    amap(transform)

/** Parallel map that swallows per-element errors (returns null on failure). */
suspend fun <A, B> Iterable<A>.amapNotNull(transform: suspend (A) -> B?): List<B> =
    coroutineScope {
        map { async { runCatching { transform(it) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

/** Execute [block] in parallel for each element, ignoring failures. */
suspend fun <A> Iterable<A>.forEach(block: suspend (A) -> Unit) {
    coroutineScope { map { async { runCatching { block(it) } } }.awaitAll() }
}

/** Run a collection of suspend lambdas in parallel; return all non-null results. */
suspend fun <T> runAllAsyncNotNull(vararg jobs: suspend () -> T?): List<T> =
    coroutineScope {
        jobs.map { async { runCatching { it() }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }
