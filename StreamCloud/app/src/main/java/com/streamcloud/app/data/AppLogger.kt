package com.streamcloud.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val MAX_ENTRIES = 500

    enum class Level { ERROR, WARN, INFO }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    val entriesFlow: StateFlow<List<Entry>> = _entries.asStateFlow()



    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message — ${throwable.javaClass.simpleName}: ${throwable.message}" else message
        Log.e(tag, full, throwable)
        add(Entry(System.currentTimeMillis(), Level.ERROR, tag, full))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message — ${throwable.javaClass.simpleName}: ${throwable.message}" else message
        Log.w(tag, full, throwable)
        add(Entry(System.currentTimeMillis(), Level.WARN, tag, full))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        add(Entry(System.currentTimeMillis(), Level.INFO, tag, message))
    }




    fun entries(): List<Entry> = synchronized(lock) { buffer.toList().reversed() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _errorCount.value = 0
            _entries.value = emptyList()
        }
    }


    fun formatAll(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return entries().joinToString("\n") { e ->
            val lvl = when (e.level) {
                Level.ERROR -> "E"
                Level.WARN  -> "W"
                Level.INFO  -> "I"
            }
            "[${sdf.format(Date(e.timeMs))}] $lvl/${e.tag}: ${e.message}"
        }
    }



    private fun add(entry: Entry) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            if (entry.level == Level.ERROR) _errorCount.value = _errorCount.value + 1
            _entries.value = buffer.toList().reversed()
        }
    }
}
