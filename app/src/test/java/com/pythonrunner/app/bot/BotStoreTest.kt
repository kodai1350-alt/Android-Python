package com.pythonrunner.app.bot

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotStoreTest {
    private val root: File = Files.createTempDirectory("store").toFile()
    private val botsRoot = File(root, "bots")
    private val prefs = MapKeyValue()

    private fun store(id: String) = BotStore(id, botsRoot, prefs)

    private fun write(dir: File, path: String, text: String = "x") =
        File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun twoBotsKeepSeparateSettings() {
        store("a").apply { mainFile = "bot.py"; envVars = mapOf("TOKEN" to "a=b;c&d"); wasRunning = true }
        store("b").apply { mainFile = "src/main.py"; envVars = mapOf("TOKEN" to "other") }
        assertEquals("bot.py", store("a").mainFile)
        assertEquals(mapOf("TOKEN" to "a=b;c&d"), store("a").envVars)
        assertTrue(store("a").wasRunning)
        assertEquals("src/main.py", store("b").mainFile)
        assertEquals(mapOf("TOKEN" to "other"), store("b").envVars)
        assertFalse(store("b").wasRunning)
    }

    @Test fun pathsAreUnderTheBotsOwnFolder() {
        val s = store("a")
        assertEquals(File(botsRoot, "a/code"), s.codeDir)
        assertEquals(File(botsRoot, "a/libs"), s.libsDir)
        assertEquals(File(botsRoot, "a/shared.manifest"), s.manifestFile)
        assertEquals(File(botsRoot, "a/import-staging"), s.stagingDir)
        assertEquals(File(botsRoot, "a/import-backup"), s.backupDir)
    }

    @Test fun deleteFileRefusesPathsOutsideTheCodeFolder() {
        val a = store("a")
        val other = write(store("b").codeDir, "x.py")
        write(a.codeDir, "main.py")
        assertFalse(a.deleteFile("../../b/code/x.py"))
        assertFalse(a.deleteFile(""))
        assertFalse(a.deleteFile("/etc"))
        assertTrue(other.exists())
        assertTrue(File(a.codeDir, "main.py").exists())
    }

    @Test fun deletingTheMainFileForgetsIt() {
        val a = store("a")
        write(a.codeDir, "bot.py")
        write(a.codeDir, "other.py")
        a.mainFile = "bot.py"
        assertTrue(a.deleteFile("other.py"))
        assertEquals("bot.py", a.mainFile)
        assertTrue(a.deleteFile("bot.py"))
        assertNull(a.mainFile)
    }

    @Test fun forgetMissingMainAfterAnImportRemovedIt() {
        val a = store("a")
        write(a.codeDir, "bot.py").delete()
        a.mainFile = "bot.py"
        a.forgetMissingMain()
        assertNull(a.mainFile)
    }

    @Test fun deleteAllRemovesFilesAndSettingsOfOnlyThatBot() {
        val a = store("a").apply { mainFile = "m.py"; envVars = mapOf("K" to "v"); wasRunning = true }
        val b = store("b").apply { mainFile = "n.py" }
        write(a.codeDir, "m.py")
        write(a.libsDir, "lib/x.py")
        write(b.codeDir, "n.py")
        a.deleteAll()
        assertFalse(a.dir.exists())
        BotStore.keys("a").forEach { assertNull(prefs.get(it), it) }
        assertTrue(File(b.codeDir, "n.py").exists())
        assertEquals("n.py", store("b").mainFile)
    }

    @Test fun keysNameEverySetting() =
        assertEquals(listOf("bot.a.mainFile", "bot.a.envVars", "bot.a.wasRunning", "bot.a.parts"), BotStore.keys("a"))

    @Test fun partsInstalledRoundTripsAndIsDeletedWithTheBot() {
        store("a").partsInstalled = mapOf("bot/requirements.txt" to 1_700_000_000_000L, "player" to 5L)
        assertEquals(mapOf("bot/requirements.txt" to 1_700_000_000_000L, "player" to 5L), store("a").partsInstalled)
        store("a").deleteAll()
        assertEquals(emptyMap(), store("a").partsInstalled)
    }

    @Test fun deletingAFolderNeverFollowsLinksOutOfIt() {
        val outside = File(root, "outside").apply { mkdirs() }
        write(outside, "keep.txt")
        val a = store("a")
        write(a.codeDir, "bot/x.py")
        val link = File(a.codeDir, "bot/linked").toPath()
        // Creating links needs a privilege on some Windows setups: then there is nothing to test here.
        if (runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isFailure) return
        assertTrue(a.deleteFile("bot"))
        assertFalse(File(a.codeDir, "bot").exists())
        assertTrue(File(outside, "keep.txt").exists())
    }
}

class MapKeyValue : KeyValue {
    val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
