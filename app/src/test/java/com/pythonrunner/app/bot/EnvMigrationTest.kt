package com.pythonrunner.app.bot

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvMigrationTest {
    private val root: File = Files.createTempDirectory("envmig").toFile()
    private val prefs = MapKeyValue()
    private val store = BotStore("b1", File(root, "bots"), prefs)
    private val shared = File(root, "shared").apply { mkdirs() }

    private fun privateEnv() = DotEnv.parse(File(store.codeDir, ".env").readText()).values

    @Test fun secretsBecomeEnvFile() {
        store.envVars = mapOf("DISCORD_TOKEN" to "abc")
        assertTrue(EnvMigration.migrate(store, null))
        assertEquals(mapOf("DISCORD_TOKEN" to "abc"), privateEnv())
        assertEquals(emptyMap(), store.envVars)
    }

    @Test fun sharedEnvIsTakenFirst() {
        File(shared, ".env").writeText("A=1\n")
        store.envVars = mapOf("B" to "2")
        assertTrue(EnvMigration.migrate(store, shared))
        assertEquals(mapOf("A" to "1", "B" to "2"), privateEnv())
    }

    @Test fun privateValueWinsAndSharedOnlyNamesAreAdded() {
        store.codeDir.mkdirs()
        File(store.codeDir, ".env").writeText("A=1\n")
        File(shared, ".env").writeText("A=9\nC=3\n")
        assertTrue(EnvMigration.migrate(store, shared))
        assertEquals(mapOf("A" to "1", "C" to "3"), privateEnv())
    }

    @Test fun secondRunChangesNothing() {
        File(shared, ".env").writeText("A=1\n")
        store.envVars = mapOf("B" to "x y")
        EnvMigration.migrate(store, shared)
        val first = File(store.codeDir, ".env").readText()
        assertFalse(EnvMigration.migrate(store, shared))
        assertEquals(first, File(store.codeDir, ".env").readText())
    }

    @Test fun nothingToDoWritesNoFile() {
        assertFalse(EnvMigration.migrate(store, shared))
        assertFalse(File(store.codeDir, ".env").exists())
    }

    @Test fun envVarsKeptWhenWritingFails() {
        store.dir.mkdirs()
        store.codeDir.writeText("not a folder")
        store.envVars = mapOf("T" to "1")
        assertFailsWith<Exception> { EnvMigration.migrate(store, null) }
        assertEquals(mapOf("T" to "1"), store.envVars)
    }

    @Test fun runsOnlyOnceSoLaterSharedEditsWaitForTheUser() {
        File(shared, ".env").writeText("TOKEN=old\n")
        EnvMigration.migrate(store, shared)
        // Later the token is rotated on a PC and a name added; the app restarts before 反映する.
        File(shared, ".env").writeText("TOKEN=new\nGUILD_ID=1\n")
        assertFalse(EnvMigration.migrate(store, shared))
        assertEquals(mapOf("TOKEN" to "old"), privateEnv())
        assertEquals("TOKEN=new\nGUILD_ID=1\n", File(shared, ".env").readText())
    }
}
