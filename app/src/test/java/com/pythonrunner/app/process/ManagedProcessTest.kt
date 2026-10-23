package com.pythonrunner.app.process

import com.pythonrunner.app.testutil.isWindows
import com.pythonrunner.app.testutil.shell
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

class ManagedProcessTest {
    private val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())
    private val exited = CountDownLatch(1)
    @Volatile private var exitCode = Int.MIN_VALUE

    private fun process(command: List<String>, env: Map<String, String> = emptyMap(), dir: File = File(".")) =
        ManagedProcess(command, env, dir, { lines += it }, { exitCode = it; exited.countDown() })

    private fun awaitExit() = assertTrue(exited.await(10, TimeUnit.SECONDS), "process did not exit")

    @Test fun capturesStdoutLinesInOrder() {
        process(shell(if (isWindows) "echo a&& echo b" else "echo a; echo b")).start()
        awaitExit()
        assertEquals(listOf("a", "b"), lines.map { it.text.trim() })
        assertTrue(lines.none { it.isError })
        assertEquals(0, exitCode)
    }

    @Test fun reportsNonZeroExitAndStderr() {
        process(shell(if (isWindows) "echo boom 1>&2 && exit 3" else "echo boom 1>&2; exit 3")).start()
        awaitExit()
        assertEquals(listOf("boom"), lines.filter { it.isError }.map { it.text.trim() })
        assertEquals(3, exitCode)
    }

    @Test fun passesEnvironmentAndWorkDir() {
        val dir = Files.createTempDirectory("mp").toFile()
        process(shell(if (isWindows) "echo %PR_TEST%& cd" else "echo \$PR_TEST; pwd"), mapOf("PR_TEST" to "ok"), dir).start()
        awaitExit()
        val out = lines.joinToString("\n") { it.text }
        assertTrue("ok" in out, out)
        assertTrue(dir.canonicalFile.name in out, out)
    }

    @Test fun forceKillsAfterGrace() {
        assumeFalse(isWindows)
        val p = process(shell("trap '' TERM; sleep 30"))
        p.start()
        Thread.sleep(300)
        val started = System.nanoTime()
        p.stop(graceMillis = 500)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
        assertFalse(p.isRunning)
        awaitExit()
    }

    @Test fun stopIsSafeWhenNotRunning() {
        process(shell("echo x")).stop()
    }
}
