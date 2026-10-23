package com.pythonrunner.app.bot

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SpikeBotStoreTest {
    private val base: File = Files.createTempDirectory("sbs").toFile()
    private val prefs = object : KeyValue {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test fun importPySavesFileAndSetsMain() {
        val store = SpikeBotStore(base, prefs)
        store.importPy("bot.py", "print(1)".byteInputStream())
        assertEquals("print(1)", File(store.botDir, "bot.py").readText())
        assertEquals("bot.py", store.mainFile)
    }

    @Test fun envVarsRoundTrip() {
        val vars = mapOf("TOKEN" to "a=b;c&d", "X" to "")
        SpikeBotStore(base, prefs).envVars = vars
        assertEquals(vars, SpikeBotStore(base, prefs).envVars)
    }

    @Test fun importPyRejectsPathTraversal() {
        val store = SpikeBotStore(base, prefs)
        assertFailsWith<IllegalArgumentException> {
            store.importPy("../evil.py", "x".byteInputStream())
        }
        assertFalse(File(store.botDir.parentFile, "evil.py").exists())
    }

    @Test fun botFolderCanLiveElsewhere() {
        val shared = File(base, "shared/Documents/PythonRunner/MyBot")
        val store = SpikeBotStore(base, prefs, botDir = shared)
        store.importPy("bot.py", "print(1)".byteInputStream())
        assertEquals("print(1)", File(shared, "bot.py").readText())
        assertEquals(File(base, "bots/spike-libs"), store.libsDir)
    }

    @Test fun wasRunningDefaultsToFalse() {
        val store = SpikeBotStore(base, prefs)
        assertFalse(store.wasRunning)
        store.wasRunning = true
        assertEquals(true, SpikeBotStore(base, prefs).wasRunning)
    }
}
