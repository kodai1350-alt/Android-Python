package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedFoldersTest {
    private val root: File = Files.createTempDirectory("PythonRunner").toFile()

    private fun write(path: String, text: String = "x") =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun unlistedSkipsBotsHiddenFoldersAndFiles() {
        File(root, "mybot").mkdirs()
        File(root, "音楽Bot").mkdirs()
        File(root, ".thumbnails").mkdirs()
        write("note.txt")
        assertEquals(listOf("音楽Bot"), SharedFolders.unlisted(root, listOf("MyBot")))
    }

    @Test fun unlistedIsEmptyWithoutTheRootFolder() =
        assertTrue(SharedFolders.unlisted(File(root, "missing"), emptyList()).isEmpty())

    @Test fun renameChangesOnlyTheCase() {
        write("MyBot/main.py", "m")
        assertTrue(SharedFolders.rename(root, "MyBot", "mybot"))
        val names = root.list()!!.toList()
        assertTrue("mybot" in names, names.toString())
        assertFalse("MyBot" in names, names.toString())
        assertEquals("m", File(root, "mybot/main.py").readText())
    }

    @Test fun renameMovesTheFolder() {
        write("MyBot/main.py", "m")
        assertTrue(SharedFolders.rename(root, "MyBot", "紬"))
        assertEquals("m", File(root, "紬/main.py").readText())
        assertFalse(File(root, "MyBot").exists())
    }

    @Test fun renameRefusesAnExistingFolder() {
        write("a/1.py")
        write("b/2.py")
        assertFalse(SharedFolders.rename(root, "a", "b"))
        assertTrue(File(root, "a/1.py").isFile)
        assertTrue(File(root, "b/2.py").isFile)
        assertFalse(File(root, "b/1.py").exists())
    }

    @Test fun adoptableFilesSkipsSecretsAndCaches() {
        listOf("src/bot.py", "persona.txt", ".env", "token.json", "__pycache__/x.pyc").forEach { write("紬/$it") }
        assertEquals(listOf("persona.txt", "src/bot.py"), SharedFolders.adoptableFiles(File(root, "紬")))
        listOf("src/bot.py", "persona.txt", ".env", "token.json", "__pycache__/x.pyc")
            .forEach { assertTrue(File(root, "紬/$it").isFile, it) }
    }
}
