package com.lagradost.cloudstream3

import kotlinx.coroutines.*

/**
 * Stubs for com.lagradost.cloudstream3.ParCollectionsKt.
 * Plugins compiled against the real CloudStream SDK call amap/apmap/pmap for
 * concurrent item processing. These stubs run concurrently via coroutineScope.
 */

/** Async map — runs all list items concurrently. */
suspend fun <V, R> List<V>.amap(f: suspend (V) -> R): List<R> = coroutineScope {
    map { async { f(it) } }.awaitAll()
}

/** Async parallel map for Map entries — runs all entries concurrently. */
suspend fun <K, V, R> Map<out K, V>.amap(f: suspend (Map.Entry<K, V>) -> R): List<R> = coroutineScope {
    map { async { f(it) } }.awaitAll()
}

/** Async parallel map — alias for amap on lists. */
suspend fun <V, R> List<V>.apmap(f: suspend (V) -> R): List<R> = coroutineScope {
    map { async { f(it) } }.awaitAll()
}

/** Async parallel map for Iterable. */
suspend fun <V, R> Iterable<V>.pmap(f: suspend (V) -> R): List<R> = coroutineScope {
    map { async { f(it) } }.awaitAll()
}

/** Async for-each — runs all items concurrently. */
suspend fun <V> List<V>.apForEach(f: suspend (V) -> Unit): Unit = coroutineScope {
    map { async { f(it) } }.awaitAll()
    Unit
}
