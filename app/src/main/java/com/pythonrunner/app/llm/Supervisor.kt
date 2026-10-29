package com.pythonrunner.app.llm

import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import kotlin.concurrent.thread

/**
 * Keeps a helper process (llama-server) alive: after an unexpected exit it logs the last lines
 * and starts it again after the next delay. Its normal output is not logged (it is very chatty).
 */
class Supervisor(
    private val create: (onLine: (LogLine) -> Unit, onExit: (Int) -> Unit) -> ManagedProcess,
    private val log: (LogLine) -> Unit,
    private val delaysMillis: List<Long> = listOf(1_000, 5_000, 30_000),
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var generation = 0
    private var stopped = true
    private var process: ManagedProcess? = null
    private var failures = 0
    private var startedAt = 0L
    private val tail = ArrayDeque<String>()

    @Volatile var restarts = 0
        private set

    fun start() {
        val gen = synchronized(lock) {
            if (!stopped) return
            stopped = false
            failures = 0
            ++generation
        }
        launch(gen)
    }

    /** Blocks for up to the process's grace period; call it off the main thread. */
    fun stop() {
        val p = synchronized(lock) {
            stopped = true
            ++generation
            process.also { process = null }
        }
        p?.stop()
    }

    private fun launch(gen: Int) {
        synchronized(lock) {
            if (stopped || gen != generation) return
            tail.clear()
            startedAt = clock()
            val p = create(::remember) { code -> onExit(gen, code) }
            process = p
            try {
                p.start()
            } catch (e: Exception) {
                process = null
                log(LogLine(clock(), "[AIモデル] 起動できませんでした: ${e.message}", true))
            }
        }
    }

    private fun remember(line: LogLine) = synchronized(tail) {
        tail.addLast(line.text)
        while (tail.size > TAIL_LINES) tail.removeFirst()
    }

    private fun onExit(gen: Int, code: Int) {
        val delay = synchronized(lock) {
            if (stopped || gen != generation) return
            process = null
            // A long healthy run starts the back-off over.
            if (clock() - startedAt >= HEALTHY_MILLIS) failures = 0
            delaysMillis[minOf(failures, delaysMillis.lastIndex)].also { failures++ }
        }
        val now = clock()
        synchronized(tail) { tail.toList() }.forEach { log(LogLine(now, "[AIモデル] $it", true)) }
        log(LogLine(now, "[AIモデル] 止まりました（終了コード $code）。立ち上げ直します", true))
        thread(name = "llm-restart") {
            sleep(delay)
            restarts++
            launch(gen)
        }
    }

    private companion object {
        const val TAIL_LINES = 30
        const val HEALTHY_MILLIS = 30 * 60 * 1000L
    }
}
