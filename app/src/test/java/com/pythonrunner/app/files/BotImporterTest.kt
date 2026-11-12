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
        put(backup, "files/main.py", "old")
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
        put(backup, "files/a.py", "old-a")
        put(backup, ".importing", "E\ta.py\nE\tb.py\nN\tnew.py\n")
        assertTrue(BotImporter.recover(botDir, backup, staging))
        assertEquals(mapOf("a.py" to "old-a", "b.py" to "old-b"), contents(botDir))
    }

    @Test fun aBotFileNamedLikeTheMarkerCannotBreakTheRollback() {
        put(botDir, ".importing", "bot's own file")
        put(botDir, "keep.db", "Z")
        put(staging, ".importing", "new")
        put(staging, "a.py", "a")
        val before = contents(botDir)
        val failing: (File, File) -> Unit = { from, to ->
            if (from.path.startsWith(staging.path) && from.name == "a.py") throw java.io.IOException("disk error")
            check(from.renameTo(to.apply { parentFile!!.mkdirs() }))
        }
        assertFailsWith<java.io.IOException> {
            BotImporter.applyPlan(BotImporter.plan(staging, botDir), staging, botDir, backup, failing)
        }
        assertEquals(before, contents(botDir))
    }

    @Test fun namesWithLineBreaksOrTabsAreNotImported() {
        val children = listOf("ok.py", "a\nN\tkeep.db", "b\tc.py", "d\r.py").map { name ->
            name to { "x".byteInputStream() as java.io.InputStream }
        }
        assertEquals(1, BotImporter.stageTree(children, staging))
        assertEquals(listOf("ok.py"), stagedPaths())
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

    @Test fun stageFileRejectsControlCharacters() {
        // A provider name like this would forge a marker line that deletes the bot's data.json.
        assertFailsWith<IllegalArgumentException> {
            BotImporter.stageFile("x\nN\tdata.json", "x".byteInputStream(), staging)
        }
    }

    @Test fun zipSizeIgnoresSkippedAndUnsafeEntries() {
        assertEquals(3L, BotImporter.zipSize(zip("a.py" to "abc", "venv/big.bin" to "xxxxxxxx", "../e.py" to "zz")))
    }

    @Test fun macZipJunkIsSkipped() {
        BotImporter.stageZip(
            zip("Bot/main.py" to "m", "__MACOSX/Bot/._main.py" to "x", "Bot/.DS_Store" to "d"),
            staging,
        )
        assertEquals(listOf("main.py"), stagedPaths())
    }

    @Test fun shiftJisCharsetIsAvailable() {
        assertTrue(BotImporter.shiftJis().newEncoder().canEncode("日本語のファイル"))
    }

    @Test fun killedWhileCleaningUpKeepsTheImport() {
        put(botDir, "main.py", "old")
        put(staging, "main.py", "new")
        put(staging, "new.py", "n")
        val killed: (File) -> Unit = { dir ->
            // Some saved originals are gone, then the app dies before the rest of the cleanup.
            File(dir, "files").deleteRecursively()
            throw IllegalStateException("killed")
        }
        assertFailsWith<IllegalStateException> {
            BotImporter.applyPlan(BotImporter.plan(staging, botDir), staging, botDir, backup, cleanup = killed)
        }
        BotImporter.recover(botDir, backup, staging)
        assertEquals(mapOf("main.py" to "new", "new.py" to "n"), contents(botDir))
    }

    @Test fun leftoverMarkerIsRecoveredBeforeANewApply() {
        // An earlier rollback died midway: data.json's original is still saved aside.
        put(botDir, "data.json", "half-imported")
        put(backup, "files/data.json", "old-data")
        put(backup, ".importing", "E\tdata.json\n")
        put(staging, "a.py", "a")
        BotImporter.applyPlan(BotImporter.plan(staging, botDir), staging, botDir, backup)
        assertEquals(mapOf("data.json" to "old-data", "a.py" to "a"), contents(botDir))
    }

    @Test fun progressEndsAt100() {
        val seen = mutableListOf<Int>()
        BotImporter.stageZip(zip("a.py" to "a", "b.py" to "b"), staging) { seen += it }
        assertEquals(100, seen.last())
        assertTrue(seen.all { it in 0..100 })
    }

    private fun write(dir: File, path: String, text: String = "x") =
        File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun tree(vararg entries: Pair<String, String>): List<Pair<String, () -> java.io.InputStream>> =
        entries.map { (path, text) -> path to { text.byteInputStream() } }

    @Test fun folderLandsUnderItsNameInTheOpenFolder() {
        val bot = File(root, "bot").apply { mkdirs() }
        BotImporter.stageTree(tree("src/a.js" to "a", "package.json" to "{}"), staging, prefix = "player")
        assertEquals(listOf("player/package.json", "player/src/a.js"), BotImporter.plan(staging, bot, scope = "player").files)
    }

    @Test fun extrasAreTheOldFilesTheNewFolderLacks() {
        val bot = File(root, "bot")
        write(bot, "player/old.js")
        write(bot, "player/node_modules/x/i.js")
        write(bot, "bot/x.py")
        BotImporter.stageTree(tree("package.json" to "{}"), staging, prefix = "player")
        assertEquals(listOf("player/old.js"), BotImporter.plan(staging, bot, scope = "player").extras)
        assertEquals(emptyList(), BotImporter.plan(staging, bot).extras)
    }

    @Test fun replacingTheWholeBotKeepsKeysAndPartsOutOfExtras() {
        val bot = File(root, "bot")
        listOf("data/secure.key", "data/sounds.dat", "bot/.venv/bin/python", ".env", "Start.py").forEach { write(bot, it) }
        BotImporter.stageTree(tree("Start.py" to "new", ".env" to "T=1"), staging)
        assertEquals(listOf("data/sounds.dat"), BotImporter.plan(staging, bot, scope = "").extras)
    }

    @Test fun removedExtrasGoAndComeBackOnRollback() {
        val bot = File(root, "bot")
        val backup = File(root, "backup")
        write(bot, "player/old.js", "old")
        BotImporter.stageTree(tree("package.json" to "{}"), staging, prefix = "player")
        val plan = BotImporter.plan(staging, bot, scope = "player")
        var moves = 0
        assertFailsWith<IllegalStateException> {
            BotImporter.applyPlan(plan, staging, bot, backup, remove = plan.extras, move = { from, to ->
                // The removal is moved aside first; placing the new file then fails.
                if (++moves == 2) error("disk full")
                to.parentFile?.mkdirs(); from.renameTo(to)
            })
        }
        assertEquals("old", File(bot, "player/old.js").readText())
        assertFalse(File(bot, "player/package.json").exists())

        BotImporter.applyPlan(plan, staging, bot, backup, remove = plan.extras)
        assertFalse(File(bot, "player/old.js").exists())
        assertTrue(File(bot, "player/package.json").isFile)
        assertFalse(backup.exists())
    }

    @Test fun zipAndFileGoIntoThePrefix() {
        BotImporter.stageFile("a.py", "a".byteInputStream(), staging, prefix = "bot")
        BotImporter.stageZip(zip("pkg/" to "", "pkg/b.py" to "b"), staging, prefix = "lib")
        assertEquals(listOf("bot/a.py", "lib/b.py"), stagedPaths())
    }

    @Test fun folderOverAFileOfTheSameNameIsRefused() {
        val bot = File(root, "bot")
        write(bot, "player", "a file")
        BotImporter.stageTree(tree("package.json" to "{}"), staging, prefix = "player")
        val plan = BotImporter.plan(staging, bot, scope = "player")
        assertFailsWith<java.io.IOException> { BotImporter.applyPlan(plan, staging, bot, File(root, "backup")) }
        assertEquals("a file", File(bot, "player").readText())
    }

    @Test fun extrasNeverReachThroughALinkOutOfTheBot() {
        val bot = File(root, "bot")
        val outside = File(root, "outside").apply { mkdirs() }
        write(outside, "precious.txt")
        write(bot, "Start.py")
        // Creating links needs a privilege on some Windows setups: then there is nothing to test here.
        if (runCatching { Files.createSymbolicLink(File(bot, "linked").toPath(), outside.toPath()) }.isFailure) return
        BotImporter.stageTree(tree("Start.py" to "new"), staging)
        assertEquals(emptyList(), BotImporter.plan(staging, bot, scope = "").extras)
    }

    @Test fun requirementsAreReadUnderThePrefix() {
        BotImporter.stageTree(tree("requirements.txt" to "discord.py"), staging, prefix = "bot")
        assertEquals("discord.py", BotImporter.requirements(staging, "bot"))
    }
}
