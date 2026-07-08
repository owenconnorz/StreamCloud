package com.lagradost.cloudstream3

import kotlinx.coroutines.*

/**
 * Stub of CloudStream's ParCollections — provides the parallel-execution helpers
 * that plugins call at runtime.  The JVM class is ParCollectionsKt so all
 * top-level functions here are accessible as static methods on that class.
 *
 * IMPORTANT: we provide BOTH Iterable<A> and List<A> receiver overloads because
 * the two generate *different* JVM static method descriptors:
 *   amap(Ljava/lang/Iterable; ...)   – used by plugins that iterate over a generic seq.
 *   amap(Ljava/util/List; ...)       – used by most CloudStream plugins (CloudPlay,
 *                                       TorraStream, UHDmoviesProvider, etc.)
 * Without the List overloads those plugins crash with:
 *   NoSuchMethodError: No static method amap(Ljava/util/List; ...)
 *                       in class Lcom/lagradost/cloudstream3/ParCollectionsKt
 */

// ── Iterable receivers (generic) ─────────────────────────────────────────────

/** Run all jobs concurrently and return their results in order. */
suspend fun <T> runAllAsync(vararg jobs: suspend () -> T): List<T> =
    coroutineScope { jobs.map { async { it() } }.awaitAll() }

/** Parallel map — Iterable receiver. */
suspend fun <A, B> Iterable<A>.amap(transform: suspend (A) -> B): List<B> =
    coroutineScope { map { async { transform(it) } }.awaitAll() }

/** Alias — Iterable receiver. */
suspend fun <A, B> Iterable<A>.apmap(transform: suspend (A) -> B): List<B> =
    amap(transform)

/** Parallel map that swallows per-element errors — Iterable receiver. */
suspend fun <A, B> Iterable<A>.amapNotNull(transform: suspend (A) -> B?): List<B> =
    coroutineScope {
        map { async { runCatching { transform(it) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

/** Execute [block] in parallel for each element — Iterable receiver. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
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

// ── List receivers (explicit) — fixes NoSuchMethodError amap(Ljava/util/List;...) ──
//
// IMPORTANT: Do NOT add @JvmName annotations to these overloads.
// Without @JvmName, Kotlin compiles them to JVM static methods whose first
// parameter type is java.util.List — which is exactly what CloudStream plugins
// call at runtime:
//   amap(Ljava/util/List;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/Continuation;)
// Adding @JvmName (e.g. @JvmName("amapList")) renames the JVM method and would
// cause the plugin's call to fail again with NoSuchMethodError.
// The Iterable and List overloads compile to *different* JVM descriptors
// (java/lang/Iterable vs java/util/List as the first parameter), so there is
// no platform-declaration clash and @JvmName is not needed.

/** Parallel map — List<A> receiver.
 *  Most CloudStream plugins call this overload; it compiles to a JVM static method
 *  whose first parameter is java.util.List, not java.lang.Iterable. */
suspend fun <A, B> List<A>.amap(transform: suspend (A) -> B): List<B> =
    coroutineScope { map { async { transform(it) } }.awaitAll() }

/** Alias — List receiver. */
suspend fun <A, B> List<A>.apmap(transform: suspend (A) -> B): List<B> =
    amap(transform)

/** Parallel map that swallows errors — List receiver. */
suspend fun <A, B> List<A>.amapNotNull(transform: suspend (A) -> B?): List<B> =
    coroutineScope {
        map { async { runCatching { transform(it) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

/** Execute [block] in parallel for each element — List receiver. */
suspend fun <A> List<A>.forEach(block: suspend (A) -> Unit) {
    coroutineScope { map { async { runCatching { block(it) } } }.awaitAll() }
}
