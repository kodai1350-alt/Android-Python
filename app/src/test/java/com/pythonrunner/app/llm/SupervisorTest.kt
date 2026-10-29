package com.pythonrunner.app.llm

import com.pythonrunner.app.process.LogLine
import com.pythonrunner.app.process.ManagedProcess
import com.pythonrunner.app.testutil.isWindows
import com.pythonrunner.app.testutil.shell
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupervisorTest {
    private val dir = File(System.getProperty("java.io.tmpdir"))
    private val logged: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())
    private val sleeps: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val created = AtomicInteger()
    private var supervisor: Supervisor? = null

    @AfterTest fun cleanUp() {
        supervisor?.stop()
    }

    private fun supervise(script: String, delays: List<Long>, sleepLatch: CountDownLatch): Supervisor =
        Supervisor(
            create = { onLine, onExit ->
                created.incrementAndGet()
                ManagedProcess(shell(script), emptyMap(), dir, onLine, onExit)
            },
            log = { logged += it },
            delaysMillis = delays,
            sleep = { ms -> sleeps += ms; sleepLatch.countDown() },
        ).also { supervisor = it }

    @Test fun restartsAfterUnexpectedExit() {
        val latch = CountDownLatch(3)
        val s = supervise("exit 1", listOf(1000, 5000), latch)
        s.start()
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        s.stop()
        assertEquals(listOf(1000L, 5000L, 5000L), sleeps.take(3))
        assertTrue(s.restarts >= 2)
    }

    @Test fun stopPreventsRestart() {
        val longRunning = if (isWindows) "ping -n 30 127.0.0.1 > nul" else "sleep 30"
        val s = supervise(longRunning, listOf(1000), CountDownLatch(1))
        s.start()
        Thread.sleep(500)
        s.stop()
        Thread.sleep(1500)
        assertEquals(1, created.get())
        assertTrue(sleeps.isEmpty())
    }

    @Test fun logsTailOnCrash() {
        val script = if (isWindows) {
            "(for /L %i in (1,1,40) do @echo line%i) & exit 1"
        } else {
            "for i in \$(seq 1 40); do echo line\$i; done; exit 1"
        }
        val latch = CountDownLatch(1)
        val s = supervise(script, listOf(1000), latch)
        s.start()
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        s.stop()
        val texts = logged.map { it.text }
        assertTrue("[AIモデル] line40" in texts, texts.toString())
        assertTrue("[AIモデル] line11" in texts)
        assertFalse("[AIモデル] line10" in texts)
    }
}
