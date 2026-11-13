package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOpsTest {
    private val root: File = Files.createTempDirectory("ops").toFile()
    private val spaces = mapOf(
        "a" to BotSpace(File(root, "a/code").apply { mkdirs() }, Trash(File(root, "a/trash"))),
        "b" to BotSpace(File(root, "b/code").apply { mkdirs() }, Trash(File(root, "b/trash"))),
    )
    private val ops = FileOps { spaces.getValue(it) }

    private fun code(bot: String) = spaces.getValue(bot).codeDir
    private fun write(bot: String, path: String, text: String = "x") =
        File(code(bot), path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun read(bot: String, path: String) = File(code(bot), path).readText()
    private fun tree(bot: String): List<String> =
        code(bot).walkTopDown().filter { it != code(bot) }
            .map { it.relativeTo(code(bot)).invariantSeparatorsPath + if (it.isFile) "=" + it.readText() else "/" }
            .sorted().toList()

    @Test fun trashMovesIntoTheBin() {
        write("a", "src/x.py")
        write("a", "x.py")
        ops.trash("a", listOf("src", "x.py"))
        assertEquals(emptyList(), tree("a"))
        assertEquals(2, spaces.getValue("a").trash.entries().size)
    }

    @Test fun renameRefusesBadAndClashingNames() {
        write("a", "x.py")
        write("a", "y.py")
        assertFailsWith<IllegalArgumentException> { ops.rename("a", "x.py", "y?.py") }
        assertFailsWith<IllegalArgumentException> { ops.rename("a", "x.py", "Y.py") }
        ops.rename("a", "x.py", "X.py")
        assertEquals(listOf("X.py=x", "y.py=x"), tree("a"))
    }

    @Test fun createUsesWindowsNames() {
        assertEquals("新しいフォルダー", ops.create("a", "", NewKind.FOLDER).path)
        assertEquals("新しいフォルダー (2)", ops.create("a", "", NewKind.FOLDER).path)
        assertEquals("新しいフォルダー/新しいテキスト ドキュメント.txt", ops.create("a", "新しいフォルダー", NewKind.TEXT).path)
        assertEquals("新しい Python ファイル.py", ops.create("a", "", NewKind.PYTHON).path)
    }

    @Test fun copyIntoTheSameFolderMakesACopyName() {
        write("a", "main.py", "m")
        val op = ops.paste(listOf(Item("a", "main.py")), cut = false, toBot = "a", toDir = "", resolution = Resolution.ReplaceAll)
        assertEquals(listOf("main - コピー.py=m", "main.py=m"), tree("a"))
        assertEquals(listOf("main - コピー.py"), (op as FileOp.Copied).created)
    }

    @Test fun clashesAreFoundInsideMergedFoldersIgnoringCase() {
        write("a", "player/src/A.js", "new")
        write("a", "player/p.json")
        write("b", "player/src/a.js", "old")
        val clashes = ops.clashes(listOf(Item("a", "player")), "b", "")
        assertEquals(listOf("player/src/a.js"), clashes.map { it.path })
    }

    @Test fun replaceAll() {
        write("a", "player/src/A.js", "new")
        write("b", "player/src/a.js", "old")
        ops.paste(listOf(Item("a", "player")), cut = false, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        assertEquals(listOf("player/", "player/src/", "player/src/A.js=new"), tree("b"))
        assertEquals("player/src/a.js", spaces.getValue("b").trash.entries().single().originalPath)
    }

    @Test fun skipAll() {
        write("a", "player/src/A.js", "new")
        write("a", "player/p.json", "p")
        write("b", "player/src/a.js", "old")
        ops.paste(listOf(Item("a", "player")), cut = false, toBot = "b", toDir = "", resolution = Resolution.SkipAll)
        assertEquals(listOf("player/", "player/p.json=p", "player/src/", "player/src/a.js=old"), tree("b"))
    }

    @Test fun keepBoth() {
        write("a", "player/src/A.js", "new")
        write("b", "player/src/a.js", "old")
        ops.paste(
            listOf(Item("a", "player")), cut = false, toBot = "b", toDir = "",
            resolution = Resolution.PerFile(mapOf("player/src/a.js" to Keep.BOTH)),
        )
        assertEquals(listOf("player/", "player/src/", "player/src/A (2).js=new", "player/src/a.js=old"), tree("b"))
    }

    @Test fun copiesLeaveOutInstalledPartsAndLinks() {
        write("a", "player/index.js")
        write("a", "player/node_modules/x/i.js")
        write("a", "bot/.venv/bin/python")
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("k")
        val linked = runCatching { Files.createSymbolicLink(File(code("a"), "player/linked").toPath(), outside.toPath()) }.isSuccess
        ops.paste(listOf(Item("a", "player"), Item("a", "bot")), cut = false, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        assertEquals(listOf("bot/", "player/", "player/index.js=x"), tree("b"))
        if (linked) assertTrue(File(outside, "keep.txt").exists())
    }

    @Test fun cutAcrossBotsMoves() {
        write("a", "player/index.js", "i")
        val op = ops.paste(listOf(Item("a", "player")), cut = true, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        assertEquals(emptyList(), tree("a"))
        assertEquals(listOf("player/", "player/index.js=i"), tree("b"))
        assertEquals(listOf(Move(Item("a", "player"), Item("b", "player"))), (op as FileOp.Moved).moves)
    }

    @Test fun pastingAFolderIntoItselfIsRefused() {
        write("a", "src/lib/x.py")
        val before = tree("a")
        val e = assertFailsWith<IllegalArgumentException> {
            ops.paste(listOf(Item("a", "src")), cut = true, toBot = "a", toDir = "src/lib", resolution = Resolution.ReplaceAll)
        }
        assertEquals("対象のフォルダーは、元のフォルダーのサブフォルダーです", e.message)
        assertFailsWith<IllegalArgumentException> {
            ops.paste(listOf(Item("a", "src")), cut = false, toBot = "a", toDir = "src", resolution = Resolution.ReplaceAll)
        }
        assertEquals(before, tree("a"))
    }

    @Test fun aFailedPasteLeavesNothingHalfDone() {
        write("a", "one.py", "1")
        val clashes = ops.clashes(listOf(Item("a", "one.py"), Item("a", "two.py")), "b", "")
        assertEquals(emptyList(), clashes)
        // two.py vanished between the check and the paste.
        assertFailsWith<Exception> {
            ops.paste(listOf(Item("a", "one.py"), Item("a", "two.py")), cut = false, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        }
        assertEquals(emptyList(), tree("b").filter { !it.endsWith("/") })
    }

    @Test fun cancellingUndoesWhatWasDone() {
        write("a", "one.py")
        write("a", "two.py")
        var asked = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            ops.paste(
                listOf(Item("a", "one.py"), Item("a", "two.py")), cut = true, toBot = "b", toDir = "",
                resolution = Resolution.ReplaceAll, cancelled = { ++asked > 1 },
            )
        }
        assertEquals(listOf("one.py=x", "two.py=x"), tree("a"))
        assertEquals(emptyList(), tree("b"))
    }

    @Test fun installedPartsCannotBeTrashedRenamedOrPastedAlone() {
        write("a", "player/node_modules/x/i.js")
        assertFailsWith<IllegalArgumentException> { ops.trash("a", listOf("player/node_modules")) }
        assertFailsWith<IllegalArgumentException> { ops.rename("a", "player/node_modules", "nm") }
        assertFailsWith<IllegalArgumentException> {
            ops.paste(listOf(Item("a", "player/node_modules/x")), cut = true, toBot = "a", toDir = "", resolution = Resolution.ReplaceAll)
        }
        // The folder around them may go.
        ops.trash("a", listOf("player"))
        assertFalse(File(code("a"), "player").exists())
    }

    @Test fun remapFollowsMovesAndRenames() {
        write("a", "bot/main.py")
        val op = ops.rename("a", "bot", "core")
        assertEquals(Item("a", "core/main.py"), ops.remap(Item("a", "bot/main.py"), op.moves))
        assertNull(ops.remap(Item("a", "other.py"), op.moves))
        assertNull(ops.remap(Item("a", "botany.py"), op.moves))
    }

    @Test fun aFileOverAFolderOfTheSameNameIsRefused() {
        write("a", "data", "a file")
        write("b", "data/keep.txt", "k")
        val before = tree("b")
        assertFailsWith<IllegalArgumentException> { ops.clashes(listOf(Item("a", "data")), "b", "") }
        assertFailsWith<IllegalArgumentException> {
            ops.paste(listOf(Item("a", "data")), cut = false, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        }
        assertEquals(before, tree("b"))
    }

    @Test fun aCutFindsClashesInsideInstalledPartsToo() {
        write("a", "x/node_modules/m/i.js", "new")
        write("b", "x/node_modules/m/i.js", "old")
        assertEquals(listOf("x/node_modules/m/i.js"), ops.clashes(listOf(Item("a", "x")), "b", "", cut = true).map { it.path })
        assertEquals(emptyList(), ops.clashes(listOf(Item("a", "x")), "b", "", cut = false))
    }

    @Test fun aCopyWithALinkCanStillBeUndone() {
        write("a", "real.py", "r")
        val outside = File(root, "outside2").apply { mkdirs() }
        if (runCatching { Files.createSymbolicLink(File(code("a"), "linked").toPath(), outside.toPath()) }.isFailure) return
        val op = ops.paste(listOf(Item("a", "linked"), Item("a", "real.py")), cut = false, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        assertEquals(listOf("real.py"), (op as FileOp.Copied).created)
        ops.undo(op)
        assertEquals(emptyList(), tree("b"))
    }
}
