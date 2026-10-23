package com.pythonrunner.app.bot

import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import com.pythonrunner.app.testutil.isWindows
import com.pythonrunner.app.testutil.shell
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotRunnerTest {
    private val statuses: MutableList<BotStatus> = Collections.synchronizedList(mutableListOf())
    private val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())
    private val finished = CountDownLatch(1)
    @Volatile private var finishedStatus: BotStatus? = null
    @Volatile private var finishedCode = Int.MIN_VALUE
    private val created = AtomicInteger()
    private val running = CountDownLatch(1)
    private var runner: BotRunner? = null

    private val listener = object : BotRunner.Listener {
        override fun onStatus(status: BotStatus, sinceMillis: Long?) {
            statuses += status
            if (status == BotStatus.RUNNING) running.countDown()
        }
        override fun onLine(line: LogLine) { lines += line }
        override fun onFinished(status: BotStatus, exitCode: Int) {
            finishedStatus = status
            finishedCode = exitCode
            finished.countDown()
        }
    }

    private val longRunning = shell(if (isWindows) "ping -n 30 127.0.0.1" else "exec sleep 30")

    private fun runner(command: List<String>, prepare: () -> Unit = {}) =
        BotRunner(
            prepare = prepare,
            createProcess = { onLine, onExit ->
                created.incrementAndGet()
                ManagedProcess(command, emptyMap(), File("."), onLine, onExit)
            },
            listener = listener,
        ).also { runner = it }

    private fun awaitFinished() = assertTrue(finished.await(15, TimeUnit.SECONDS), "runner did not finish")

    @AfterTest fun cleanUp() {
        runner?.stop()
    }

    @Test fun stopDuringStartingNeverLaunches() {
        val gate = CountDownLatch(1)
        val r = runner(longRunning) { gate.await() }
        assertTrue(r.start())
        r.stop()
        gate.countDown()
        awaitFinished()
        assertEquals(BotStatus.STOPPED, finishedStatus)
        assertEquals(0, created.get())
        assertFalse(r.isActive)
    }

    @Test fun doubleStartLaunchesOnce() {
        val gate = CountDownLatch(1)
        val r = runner(longRunning) { gate.await() }
        assertTrue(r.start())
        assertFalse(r.start())
        gate.countDown()
        assertTrue(running.await(10, TimeUnit.SECONDS))
        r.stop()
        awaitFinished()
        assertEquals(1, created.get())
    }

    @Test fun immediateExitIsCrashedNotRunning() {
        val r = runner(shell("exit 1"))
        r.start()
        awaitFinished()
        assertEquals(BotStatus.CRASHED, finishedStatus)
        assertEquals(BotStatus.CRASHED, statuses.last())
        assertFalse(r.isActive)
    }

    @Test fun userStopIsStopped() {
        val r = runner(longRunning)
        r.start()
        assertTrue(running.await(10, TimeUnit.SECONDS))
        assertTrue(r.isActive)
        r.stop()
        awaitFinished()
        assertEquals(BotStatus.STOPPED, finishedStatus)
        assertEquals(BotStatus.STOPPED, statuses.last())
    }

    @Test fun prepareFailureIsCrashed() {
        val r = runner(longRunning) { throw IOException("disk full") }
        r.start()
        awaitFinished()
        assertEquals(BotStatus.CRASHED, finishedStatus)
        assertEquals(-1, finishedCode)
        assertTrue(lines.any { it.isError && "disk full" in it.text })
        assertEquals(0, created.get())
    }

    @Test fun canStartAgainAfterFinish() {
        val r = runner(shell("exit 0"))
        r.start()
        awaitFinished()
        assertTrue(r.start())
    }
}
