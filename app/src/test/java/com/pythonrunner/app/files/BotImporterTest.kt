package com.pythonrunner.app.files

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotImporterTest {
    private val root: File = Files.createTempDirectory("import").toFile()
    private val staging = File(root, "staging")

    /** A ZIP of (name, text) entries; a name ending in "/" is a folder entry. */
    private fun zip(vararg entries: Pair<String, String>, charset: Charset = Charsets.UTF_8): File {
        val file = File(root, "in-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream(), charset).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun stagedPaths(): List<String> =
        staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).invariantSeparatorsPath }.sorted().toList()

    @Test fun stagesFilesAndFolders() {
        val count = BotImporter.stageZip(zip("main.py" to "m", "cogs/a.py" to "a", "config.json" to "{}"), staging)
        assertEquals(3, count)
        assertEquals(listOf("cogs/a.py", "config.json", "main.py"), stagedPaths())
        assertEquals("a", File(staging, "cogs/a.py").readText())
    }

    @Test fun stripsSingleTopFolder() {
        BotImporter.stageZip(zip("repo-main/" to "", "repo-main/main.py" to "m", "repo-main/cogs/a.py" to "a"), staging)
        assertEquals(listOf("cogs/a.py", "main.py"), stagedPaths())
    }

    @Test fun keepsTopFolderWhenThereAreSeveral() {
        BotImporter.stageZip(zip("a/x.py" to "x", "b/y.py" to "y"), staging)
        assertEquals(listOf("a/x.py", "b/y.py"), stagedPaths())
    }

    @Test fun skipsJunkFolders() {
        BotImporter.stageZip(
            zip(
                "main.py" to "m", "__pycache__/m.pyc" to "c", ".git/HEAD" to "h",
                "venv/bin/python" to "p", "sub/.venv/x" to "x", "cogs/__pycache__/a.pyc" to "c",
            ),
            staging,
        )
        assertEquals(listOf("main.py"), stagedPaths())
        assertTrue(BotImporter.isSkipped("cogs/__pycache__/a.pyc"))
        assertFalse(BotImporter.isSkipped("cogs/a.py"))
    }

    @Test fun rejectsZipSlip() {
        BotImporter.stageZip(zip("ok.py" to "o", "../evil.py" to "e", "/abs.py" to "a", "a/../../up.py" to "u"), staging)
        assertEquals(listOf("ok.py"), stagedPaths())
        assertFalse(File(root, "evil.py").exists())
        assertFalse(File(root, "up.py").exists())
    }

    @Test fun readsShiftJisNames() {
        BotImporter.stageZip(zip("日本語.py" to "j", "フォルダ/設定.json" to "{}", charset = Charset.forName("MS932")), staging)
        assertEquals(listOf("フォルダ/設定.json", "日本語.py"), stagedPaths())
    }

    @Test fun emptyZipStagesNothing() {
        assertEquals(0, BotImporter.stageZip(zip("only/" to "", "only/deeper/" to ""), staging))
        assertEquals(emptyList(), stagedPaths())
    }

    @Test fun zipSizeSumsUncompressedSizes() {
        assertEquals(7L, BotImporter.zipSize(zip("a.py" to "abc", "b/c.py" to "defg")))
    }

    private val botDir = File(root, "bot")

    private fun put(dir: File, path: String, text: String) =
        File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun stageFilePutsItAtTheTop() {
        BotImporter.stageFile("bot.py", "print(1)".byteInputStream(), staging)
        assertEquals("print(1)", File(staging, "bot.py").readText())
        assertFailsWith<IllegalArgumentException> { BotImporter.stageFile("../x.py", "x".byteInputStream(), staging) }
        assertFailsWith<IllegalArgumentException> { BotImporter.stageFile("", "x".byteInputStream(), staging) }
    }

    @Test fun stageTreeSkipsJunkAndUnsafePaths() {
        val children = listOf(
            "main.py" to "m", "cogs/a.py" to "a", "__pycache__/x.pyc" to "c", "../evil.py" to "e",
        ).map { (path, text) -> path to { text.byteInputStream() as java.io.InputStream } }
        assertEquals(2, BotImporter.stageTree(children, staging))
        assertEquals(listOf("cogs/a.py", "main.py"), stagedPaths())
    }

    @Test fun planListsOnlyChangedExistingFilesAsOverwrites() {
        put(botDir, "main.py", "A")
        put(botDir, "data.json", "X")
        put(botDir, "keep.db", "Z")
        put(staging, "main.py", "B")
        put(staging, "data.json", "X")
        put(staging, "new.py", "N")
        val plan = BotImporter.plan(staging, botDir)
        assertEquals(listOf("main.py"), plan.overwrites)
        assertEquals(listOf("data.json", "main.py", "new.py"), plan.files)
    }

    @Test fun planCountsBytes() {
        put(staging, "a.py", "abc")
        put(staging, "b/c.py", "de")
        assertEquals(5L, BotImporter.plan(staging, botDir).bytes)
    }

    @Test fun missingBytesKeepsAMargin() {
        val margin = 50L * 1024 * 1024
        assertEquals(1L, BotImporter.missingBytes(100, margin + 99))
        assertEquals(0L, BotImporter.missingBytes(100, margin + 100))
    }

    @Test fun progressEndsAt100() {
        val seen = mutableListOf<Int>()
        BotImporter.stageZip(zip("a.py" to "a", "b.py" to "b"), staging) { seen += it }
        assertEquals(100, seen.last())
        assertTrue(seen.all { it in 0..100 })
    }
}
