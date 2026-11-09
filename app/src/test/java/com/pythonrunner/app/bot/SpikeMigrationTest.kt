package com.pythonrunner.app.bot

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpikeMigrationTest {
    private val filesDir: File = Files.createTempDirectory("migrate").toFile()
    private val registry = BotRegistry(File(filesDir, "bots/registry.txt"))
    private val oldPrefs = MapKeyValue()
    private val newPrefs = MapKeyValue()

    private fun write(path: String, text: String = "x") =
        File(filesDir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun run() = SpikeMigration.run(filesDir, registry, oldPrefs, newPrefs)

    private fun store() = BotStore("mybot", File(filesDir, "bots"), newPrefs)

    private fun oldBot() {
        write("bots/spike/main.py", "print(1)")
        write("bots/spike/src/a.py", "a")
        write("bots/spike-libs/discord/__init__.py", "d")
        write("bots/spike-shared.manifest", "abc\tmain.py\n")
        oldPrefs.put("spike.mainFile", "main.py")
        oldPrefs.put("spike.envVars", "TOKEN=t")
        oldPrefs.put("spike.wasRunning", "true")
    }

    @Test fun movesFilesLibsManifestAndSettings() {
        oldBot()
        assertTrue(run())
        val s = store()
        assertEquals("print(1)", File(s.codeDir, "main.py").readText())
        assertEquals("a", File(s.codeDir, "src/a.py").readText())
        assertTrue(File(s.libsDir, "discord/__init__.py").isFile)
        assertEquals("abc\tmain.py\n", s.manifestFile.readText())
        assertFalse(File(filesDir, "bots/spike").exists())
        assertFalse(File(filesDir, "bots/spike-libs").exists())
        assertFalse(File(filesDir, "bots/spike-shared.manifest").exists())
        assertEquals("main.py", s.mainFile)
        assertEquals(mapOf("TOKEN" to "t"), s.envVars)
        assertTrue(s.wasRunning)
        assertNull(oldPrefs.get("spike.envVars"))
        assertEquals(listOf(BotEntry("mybot", "MyBot")), registry.all())
    }

    @Test fun freshInstallCreatesNothing() {
        assertFalse(run())
        assertTrue(registry.all().isEmpty())
        assertFalse(File(filesDir, "bots/mybot").exists())
    }

    @Test fun keepsSecretsWhenTheCodeFolderIsGone() {
        oldPrefs.put("spike.mainFile", "main.py")
        oldPrefs.put("spike.envVars", "TOKEN=t")
        assertTrue(run())
        assertEquals(listOf(BotEntry("mybot", "MyBot")), registry.all())
        assertEquals(mapOf("TOKEN" to "t"), store().envVars)
        assertEquals("main.py", store().mainFile)
    }

    @Test fun runningTwiceChangesNothingMore() {
        oldBot()
        run()
        val before = newPrefs.map.toMap()
        assertFalse(run())
        assertEquals(before, newPrefs.map)
        assertEquals(1, registry.all().size)
        assertEquals("print(1)", File(store().codeDir, "main.py").readText())
    }

    @Test fun resumesAfterAKillHalfway() {
        oldBot()
        registry.add("MyBot", "mybot")
        File(filesDir, "bots/mybot").mkdirs()
        assertTrue(File(filesDir, "bots/spike-libs").renameTo(File(filesDir, "bots/mybot/libs")))
        assertTrue(run())
        val s = store()
        assertTrue(File(s.codeDir, "main.py").isFile)
        assertTrue(File(s.libsDir, "discord/__init__.py").isFile)
        assertEquals(1, registry.all().size)
        assertEquals(mapOf("TOKEN" to "t"), s.envVars)
    }

    @Test fun neverOverwritesSettingsAlreadyMoved() {
        oldBot()
        newPrefs.put("bot.mybot.envVars", "TOKEN=new")
        run()
        assertEquals(mapOf("TOKEN" to "new"), store().envVars)
        assertNull(oldPrefs.get("spike.envVars"))
    }

    @Test fun undoesALeftoverImportBeforeMoving() {
        oldBot()
        write("bots/spike/new.py", "half imported")
        write("import-backup/.importing", "N\tnew.py\n")
        write("import-staging/new.py", "staged")
        run()
        assertFalse(File(store().codeDir, "new.py").exists())
        assertTrue(File(store().codeDir, "main.py").isFile)
        assertFalse(File(filesDir, "import-backup").exists())
        assertFalse(File(filesDir, "import-staging").exists())
    }
}
