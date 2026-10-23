package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderMigrationTest {
    private val root: File = Files.createTempDirectory("mig").toFile()
    private val from = File(root, "internal/bots/spike")
    private val to = File(root, "shared/Documents/PythonRunner/MyBot")

    private fun write(dir: File, path: String, text: String) =
        File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun copiesWholeTreeIntoMissingTarget() {
        write(from, "main.py", "print(1)")
        write(from, "data/a.json", "{}")
        assertTrue(FolderMigration.copyIfTargetEmpty(from, to))
        assertEquals("print(1)", File(to, "main.py").readText())
        assertEquals("{}", File(to, "data/a.json").readText())
        assertTrue(File(from, "main.py").exists(), "the original is kept")
    }

    @Test fun neverOverwritesAFolderThatAlreadyHasFiles() {
        write(from, "main.py", "old")
        write(to, "main.py", "new")
        assertFalse(FolderMigration.copyIfTargetEmpty(from, to))
        assertEquals("new", File(to, "main.py").readText())
    }

    @Test fun copiesIntoAnEmptyExistingTarget() {
        write(from, "main.py", "print(1)")
        to.mkdirs()
        assertTrue(FolderMigration.copyIfTargetEmpty(from, to))
        assertEquals("print(1)", File(to, "main.py").readText())
    }

    @Test fun nothingToCopyWhenSourceIsMissingOrEmpty() {
        assertFalse(FolderMigration.copyIfTargetEmpty(from, to))
        from.mkdirs()
        assertFalse(FolderMigration.copyIfTargetEmpty(from, to))
    }

    @Test fun skipsPythonCaches() {
        write(from, "main.py", "x")
        write(from, "__pycache__/main.cpython-314.pyc", "x")
        FolderMigration.copyIfTargetEmpty(from, to)
        assertFalse(File(to, "__pycache__").exists())
    }
}
