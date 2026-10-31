package com.pythonrunner.app.process

data class LogLine(val timeMillis: Long, val text: String, val isError: Boolean)

/** Keeps only the latest [capacity] lines so a chatty process cannot exhaust memory. */
class LogBuffer(private val capacity: Int = 2000) {
    private val lines = ArrayDeque<LogLine>()

    @Synchronized
    fun add(line: LogLine) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<LogLine> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
