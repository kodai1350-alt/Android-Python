package com.pythonrunner.app.bot

import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import kotlin.concurrent.thread

/**
 * Owns one bot's lifecycle: start, stop and exit are serialized by one lock and
 * tagged with a generation, so a stop while starting, a double start, or a late
 * exit can never leave a process that nobody tracks.
 */
class BotRunner(
    private val prepare: () -> Unit,
    private val createProcess: (onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) -> ManagedProcess,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: BotStatus, sinceMillis: Long?)
        fun onLine(line: LogLine)
        fun onFinished(status: BotStatus, exitCode: Int)
    }

    private val lock = Any()
    private var generation = 0
    private var starting = false
    private var process: ManagedProcess? = null
    private var stopRequested = false

    val isActive: Boolean get() = synchronized(lock) { starting || process != null }

    /** Returns false if the bot is already starting or running. */
    fun start(): Boolean {
        val gen = synchronized(lock) {
            if (starting || process != null) return false
            starting = true
            stopRequested = false
            ++generation
        }
        listener.onStatus(BotStatus.STARTING, null)
        thread(name = "bot-start") { launch(gen) }
        return true
    }

    /** Blocks for up to the process's grace period; call it off the main thread. */
    fun stop() {
        val p = synchronized(lock) {
            stopRequested = true
            process
        }
        p?.stop()
    }

    private fun launch(gen: Int) {
        try {
            prepare()
        } catch (e: Exception) {
            listener.onLine(LogLine(System.currentTimeMillis(), "起動できませんでした: ${e.message}", true))
            finish(gen, -1)
            return
        }
        var failure: Exception? = null
        synchronized(lock) {
            if (gen != generation || stopRequested) return@synchronized
            val p = createProcess(listener::onLine) { code -> finish(gen, code) }
            process = p
            starting = false
            // Published before start() so an immediate exit cannot be overwritten by RUNNING.
            listener.onStatus(BotStatus.RUNNING, System.currentTimeMillis())
            try {
                p.start()
                return
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let {
            listener.onLine(LogLine(System.currentTimeMillis(), "起動できませんでした: ${it.message}", true))
            finish(gen, -1)
            return
        }
        // Stop was requested while starting: nothing was launched.
        finish(gen, 0)
    }

    private fun finish(gen: Int, exitCode: Int) {
        val status = synchronized(lock) {
            if (gen != generation || (!starting && process == null)) return
            process = null
            starting = false
            statusForExit(exitCode, stopRequested)
        }
        listener.onStatus(status, null)
        listener.onFinished(status, exitCode)
    }
}
