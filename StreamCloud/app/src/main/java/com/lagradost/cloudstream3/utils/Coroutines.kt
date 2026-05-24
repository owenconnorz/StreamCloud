package com.lagradost.cloudstream3.utils

import kotlinx.coroutines.*
import java.util.Collections.synchronizedList

object Coroutines {
    fun <T> T.main(work: suspend ((T) -> Unit)): Job {
        val value = this
        return CoroutineScope(Dispatchers.Main).launch {
            try { work(value) } catch (_: Exception) {}
        }
    }

    fun <T> T.ioSafe(work: suspend CoroutineScope.(T) -> Unit): Job {
        val value = this
        return CoroutineScope(Dispatchers.IO).launch {
            try { work(value) } catch (_: Exception) {}
        }
    }

    suspend fun <T, V> V.ioWorkSafe(work: suspend CoroutineScope.(V) -> T): T? {
        val value = this
        return withContext(Dispatchers.IO) {
            try { work(value) } catch (_: Exception) { null }
        }
    }

    suspend fun <T, V> V.ioWork(work: suspend CoroutineScope.(V) -> T): T {
        val value = this
        return withContext(Dispatchers.IO) { work(value) }
    }

    suspend fun <T, V> V.mainWork(work: suspend CoroutineScope.(V) -> T): T {
        val value = this
        return withContext(Dispatchers.Main) { work(value) }
    }

    fun runOnMainThread(work: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { work() }
    }

    fun <T> threadSafeListOf(vararg items: T): MutableList<T> =
        synchronizedList(items.toMutableList())
}
