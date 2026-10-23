package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTreeTest {
    private val root: File = Files.createTempDirectory("ft").toFile()

    private fun file(path: String, text: String = "x") =
        File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun foldersFirstThenFilesByName() {
        file("main.py")
        file("Bot.py")
        file("cogs/music.py")
        file("data/a.json")
        assertEquals(listOf("cogs", "data", "Bot.py", "main.py"), FileTree.list(root, root).map { it.name })
    }

    @Test fun entriesCarryRelativePathAndDetails() {
        file("cogs/music.py", "12345")
        file("cogs/admin.py")
        val dir = FileTree.list(root, root).single()
        assertEquals("cogs", dir.relativePath)
        assertEquals(true, dir.isDirectory)
        assertEquals(2, dir.childCount)
        val music = FileTree.list(File(root, "cogs"), root).single { it.name == "music.py" }
        assertEquals("cogs/music.py", music.relativePath)
        assertEquals(5L, music.sizeBytes)
        assertEquals(false, music.isDirectory)
    }

    @Test fun hidesPythonCaches() {
        file("main.py")
        file("__pycache__/main.cpython-314.pyc")
        file("cogs/__pycache__/x.pyc")
        assertEquals(listOf("cogs", "main.py"), FileTree.list(root, root).map { it.name })
        assertEquals(0, FileTree.list(root, root).first().childCount)
    }

    @Test fun missingFolderIsEmpty() {
        assertEquals(emptyList(), FileTree.list(File(root, "nope"), root))
    }

    @Test fun breadcrumbsFromRootToFolder() {
        assertEquals(listOf("spike" to "", "cogs" to "cogs", "data" to "cogs/data"), FileTree.breadcrumbs("spike", "cogs/data"))
        assertEquals(listOf("spike" to ""), FileTree.breadcrumbs("spike", ""))
    }

    @Test fun parentFolder() {
        assertEquals("cogs", FileTree.parentOf("cogs/data"))
        assertEquals("", FileTree.parentOf("cogs"))
        assertEquals("", FileTree.parentOf(""))
    }

    @Test fun formatsSizes() {
        assertEquals("0 B", FileTree.formatSize(0))
        assertEquals("999 B", FileTree.formatSize(999))
        assertEquals("4 KB", FileTree.formatSize(4096))
        assertEquals("1.5 MB", FileTree.formatSize(1_572_864))
    }

    @Test fun iconsByKind() {
        assertEquals("📁", FileTree.icon("cogs", isDirectory = true))
        assertEquals("🐍", FileTree.icon("main.py", isDirectory = false))
        assertEquals("🔑", FileTree.icon(".env", isDirectory = false))
        assertEquals("📋", FileTree.icon("requirements.txt", isDirectory = false))
        assertEquals("⚙️", FileTree.icon("config.json", isDirectory = false))
        assertEquals("📝", FileTree.icon("README.md", isDirectory = false))
        assertEquals("🖼", FileTree.icon("icon.PNG", isDirectory = false))
        assertEquals("📄", FileTree.icon("data.bin", isDirectory = false))
    }
}
