package com.pythonrunner.app.process

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One OS process whose stdout and stderr are delivered line by line. */
class ManagedProcess(
    private val command: List<String>,
    private val env: Map<String, String>,
    private val workDir: File,
    private val onLine: (LogLine) -> Unit,
    private val onExit: (exitCode: Int) -> Unit,
) {
    @Volatile private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    fun start() {
        check(process == null) { "already started" }
        val builder = ProcessBuilder(command).directory(workDir)
        builder.environment().putAll(env)
        val p = builder.start()
        process = p
        val readers = listOf(
            pump(p.inputStream, isError = false),
            pump(p.errorStream, isError = true),
        )
        thread(name = "process-wait", isDaemon = true) {
            val code = p.waitFor()
            readers.forEach { it.join() }
            onExit(code)
        }
    }

    fun stop(graceMillis: Long = 5000) {
        val p = process ?: return
        if (!p.isAlive) return
        p.destroy()
        if (!p.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            p.waitFor()
        }
    }

    private fun pump(stream: InputStream, isError: Boolean) =
        thread(name = if (isError) "process-stderr" else "process-stdout", isDaemon = true) {
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { onLine(LogLine(System.currentTimeMillis(), it, isError)) }
            }
        }
}
