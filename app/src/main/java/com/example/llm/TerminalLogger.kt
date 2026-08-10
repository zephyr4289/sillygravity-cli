package com.example.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

object TerminalLogger {
    private const val MAX_LOGS = 200
    private val buffer = ArrayDeque<String>(MAX_LOGS)
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    @JvmStatic
    @Synchronized
    fun log(message: String) {
        if (buffer.size >= MAX_LOGS) {
            buffer.removeFirst()
        }
        buffer.addLast("> $message")
        _logs.value = buffer.toList()
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        buffer.clear()
        _logs.value = emptyList()
    }
}
