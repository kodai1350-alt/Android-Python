package com.pythonrunner.app.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrashTest {
    private val root: File = Files.createTempDirectory("trash").toFile()
    private val code = File(root, "code")
    private val trash = Trash(File(root, "trash"))

    private fun write(path: String, text: String = "x") =
        File(code, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun putAndTakeBringAFileBack() {
        write("a.py", "hello")
        trash.put(File(code, "a.py"), "a.py")
        val entry = trash.entries().single()
        assertEquals("a.py", entry.originalPath)
        assertFalse(entry.isDirectory)
        assertEquals(5L, entry.size)
        assertFalse(File(code, "a.py").exists())
        trash.take(entry, File(code, "a.py"))
        assertEquals("hello", File(code, "a.py").readText())
        assertTrue(trash.entries().isEmpty())
    }

    @Test fun foldersKeepTheirContents() {
        write("src/x/y.txt", "y")
        trash.put(File(code, "src"), "src")
        val entry = trash.entries().single()
        assertTrue(entry.isDirectory)
        trash.take(entry, File(code, "deep/src"))
        assertEquals("y", File(code, "deep/src/x/y.txt").readText())
    }

    @Test fun newestFirstAndSurvivesARestart() {
        write("a.py")
        write("b.py")
        trash.put(File(code, "a.py"), "a.py", now = 1)
        trash.put(File(code, "b.py"), "b.py", now = 2)
        assertEquals(listOf("b.py", "a.py"), Trash(File(root, "trash")).entries().map { it.originalPath })
        assertEquals(2L, Trash(File(root, "trash")).entries().first().deletedAt)
    }

    @Test fun takeRefusesToOverwrite() {
        write("a.py", "old")
        trash.put(File(code, "a.py"), "a.py")
        write("a.py", "new")
        assertFailsWith<IOException> { trash.take(trash.entries().single(), File(code, "a.py")) }
        assertEquals(1, trash.entries().size)
        assertEquals("new", File(code, "a.py").readText())
    }

    @Test fun emptyNeverFollowsLinks() {
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("k")
        write("src/a.py")
        // Creating links needs a privilege on some Windows setups: then there is nothing to test here.
        if (runCatching { Files.createSymbolicLink(File(code, "src/linked").toPath(), outside.toPath()) }.isFailure) return
        trash.put(File(code, "src"), "src")
        trash.empty()
        assertTrue(File(outside, "keep.txt").exists())
        assertTrue(trash.entries().isEmpty())
    }

    @Test fun brokenEntriesAreSkipped() {
        File(root, "trash/x").mkdirs()
        write("a.py")
        trash.put(File(code, "a.py"), "a.py")
        assertEquals(listOf("a.py"), trash.entries().map { it.originalPath })
    }

    @Test fun deleteAndSize() {
        write("a.py", "12345")
        write("b.py", "123")
        trash.put(File(code, "a.py"), "a.py")
        trash.put(File(code, "b.py"), "b.py")
        assertEquals(8L, trash.size())
        trash.delete(trash.entries().first { it.originalPath == "a.py" })
        assertEquals(listOf("b.py"), trash.entries().map { it.originalPath })
    }

    @Test fun samePathTwiceKeepsBoth() {
        write("a.py", "1")
        trash.put(File(code, "a.py"), "a.py", now = 5)
        write("a.py", "2")
        trash.put(File(code, "a.py"), "a.py", now = 5)
        assertEquals(2, trash.entries().size)
    }
}
