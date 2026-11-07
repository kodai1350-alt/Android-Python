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

    private val backup = File(root, "backup")

    private fun contents(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }

    @Test fun applyOverwritesAndKeepsOtherFiles() {
        put(botDir, "main.py", "old")
        put(botDir, "keep.db", "Z")
        put(staging, "main.py", "new")
        put(staging, "cogs/a.py", "a")
        BotImporter.applyPlan(BotImporter.plan(staging, botDir), staging, botDir, backup)
        assertEquals(mapOf("main.py" to "new", "keep.db" to "Z", "cogs/a.py" to "a"), contents(botDir))
        assertEquals(emptyMap(), contents(backup))
        assertEquals(emptyMap(), contents(staging))
    }

    @Test fun failureMidwayRestoresTheBotFolder() {
        put(botDir, "a.py", "old-a")
        put(botDir, "b.py", "old-b")
        put(botDir, "keep.db", "Z")
        put(staging, "a.py", "new-a")
        put(staging, "b.py", "new-b")
        put(staging, "new.py", "n")
        val before = contents(botDir)
        var moves = 0
        val failing: (File, File) -> Unit = { from, to ->
            // Moving the old files aside works; the second new file fails.
            if (from.path.startsWith(staging.path) && ++moves == 2) throw java.io.IOException("disk error")
            check(from.renameTo(to.apply { parentFile!!.mkdirs() }))
        }
        assertFailsWith<java.io.IOException> {
            BotImporter.applyPlan(BotImporter.plan(staging, botDir), staging, botDir, backup, failing)
        }
        assertEquals(before, contents(botDir))
    }

    @Test fun fileOverAFolderOfTheSameNameFailsCleanly() {
        put(botDir, "data/x.json", "{}")
        put(staging, "data", "file")
        put(staging, "main.py", "m")
        val before = contents(botDir)
        assertFailsWith<java.io.IOException> {
            BotImporter.applyPlan(BotImporter.plan(staging, botDir), staging, botDir, backup)
        }
        assertEquals(before, contents(botDir))
    }

    @Test fun recoverRestoresAnInterruptedApply() {
        put(botDir, "main.py", "new")
        put(botDir, "new.py", "n")
        put(botDir, "keep.db", "Z")
        put(backup, "main.py", "old")
        put(backup, ".importing", "E\tmain.py\nN\tnew.py\n")
        put(staging, "left.py", "x")
        assertTrue(BotImporter.recover(botDir, backup, staging))
        assertEquals(mapOf("main.py" to "old", "keep.db" to "Z"), contents(botDir))
        assertFalse(staging.exists())
        assertFalse(File(backup, ".importing").exists())
    }

    @Test fun recoverLeavesOriginalsThatWereNotMovedAsideYet() {
        // Interrupted while moving the old files aside: b.py was never touched.
        put(botDir, "b.py", "old-b")
        put(backup, "a.py", "old-a")
        put(backup, ".importing", "E\ta.py\nE\tb.py\nN\tnew.py\n")
        assertTrue(BotImporter.recover(botDir, backup, staging))
        assertEquals(mapOf("a.py" to "old-a", "b.py" to "old-b"), contents(botDir))
    }

    @Test fun recoverWithNothingToDoReturnsFalse() {
        put(botDir, "main.py", "m")
        assertFalse(BotImporter.recover(botDir, backup, staging))
        assertEquals(mapOf("main.py" to "m"), contents(botDir))
    }

    @Test fun pickMainOrder() {
        put(botDir, "bot.py", "")
        put(botDir, "app.py", "")
        assertEquals("bot.py", BotImporter.pickMain(botDir, null))
        put(botDir, "main.py", "")
        assertEquals("main.py", BotImporter.pickMain(botDir, null))
        put(botDir, "x.py", "")
        assertEquals("x.py", BotImporter.pickMain(botDir, "x.py"))
        assertEquals("main.py", BotImporter.pickMain(botDir, "gone.py"))
        val other = File(root, "other")
        put(other, "run.py", "")
        put(other, "lib/helper.py", "")
        assertEquals("run.py", BotImporter.pickMain(other, null))
        put(other, "b.py", "")
        assertEquals(null, BotImporter.pickMain(other, null))
    }

    @Test fun requirementsReadsTopLevelOnly() {
        put(staging, "sub/requirements.txt", "deep")
        assertEquals(null, BotImporter.requirements(staging))
        put(staging, "requirements.txt", "discord.py\naiohttp\n")
        assertEquals("discord.py\naiohttp\n", BotImporter.requirements(staging))
    }

    @Test fun progressEndsAt100() {
        val seen = mutableListOf<Int>()
        BotImporter.stageZip(zip("a.py" to "a", "b.py" to "b"), staging) { seen += it }
        assertEquals(100, seen.last())
        assertTrue(seen.all { it in 0..100 })
    }
}
