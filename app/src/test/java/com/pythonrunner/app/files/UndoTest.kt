package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UndoTest {
    private val root: File = Files.createTempDirectory("undo").toFile()
    private val spaces = mapOf(
        "a" to BotSpace(File(root, "a/code").apply { mkdirs() }, Trash(File(root, "a/trash"))),
        "b" to BotSpace(File(root, "b/code").apply { mkdirs() }, Trash(File(root, "b/trash"))),
    )
    private val ops = FileOps { spaces.getValue(it) }

    private fun code(bot: String) = spaces.getValue(bot).codeDir
    private fun write(bot: String, path: String, text: String = "x") =
        File(code(bot), path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun tree(bot: String): List<String> =
        code(bot).walkTopDown().filter { it != code(bot) }
            .map { it.relativeTo(code(bot)).invariantSeparatorsPath + if (it.isFile) "=" + it.readText() else "/" }
            .sorted().toList()

    private fun roundTrip(op: () -> FileOp) {
        write("a", "src/x.py", "1")
        write("a", "main.py", "m")
        val before = tree("a") to tree("b")
        ops.undo(op())
        assertEquals(before, tree("a") to tree("b"))
    }

    @Test fun historyKeepsTheLast20() {
        val history = UndoHistory()
        val first = FileOp.Created("a", "first", emptyList())
        history.push(first)
        repeat(20) { history.push(FileOp.Created("a", "n$it", emptyList())) }
        assertEquals(20, history.size)
        assertEquals(FileOp.Created("a", "n19", emptyList()), history.peek())
        val popped = generateSequence { history.pop() }.toList()
        assertTrue(first !in popped)
        assertEquals(null, history.pop())
    }

    @Test fun undoTrash() = roundTrip { ops.trash("a", listOf("src", "main.py")) }

    @Test fun undoRename() = roundTrip { ops.rename("a", "src", "lib") }

    @Test fun undoRenameOfTheCaseOnly() = roundTrip { ops.rename("a", "main.py", "Main.py") }

    @Test fun undoCopy() = roundTrip { ops.paste(listOf(Item("a", "src")), false, "a", "", Resolution.ReplaceAll) }

    @Test fun undoCreate() = roundTrip { ops.create("a", "src", NewKind.PYTHON) }

    @Test fun undoRestore() {
        write("a", "src/x.py", "1")
        val trashed = ops.trash("a", listOf("src"))
        val before = tree("a")
        val restored = ops.restore("a", trashed.entries, Resolution.ReplaceAll)
        ops.undo(restored)
        assertEquals(before, tree("a"))
        assertEquals(1, spaces.getValue("a").trash.entries().size)
    }

    @Test fun undoAcrossBotsReturnsTheFilesToTheirBot() {
        write("a", "player/index.js", "i")
        val op = ops.paste(listOf(Item("a", "player")), cut = true, toBot = "b", toDir = "", resolution = Resolution.ReplaceAll)
        ops.undo(op)
        assertEquals(listOf("player/", "player/index.js=i"), tree("a"))
        assertEquals(emptyList(), tree("b"))
    }

    @Test fun undoAfterTheFileChangedStops() {
        write("a", "x.py", "1")
        val op = ops.paste(listOf(Item("a", "x.py")), false, "a", "", Resolution.ReplaceAll)
        File(code("a"), "x - コピー.py").appendText("more")
        val e = assertFailsWith<IllegalStateException> { ops.undo(op) }
        assertEquals("元に戻せませんでした（ファイルが変わっています）", e.message)
        assertEquals("1more", File(code("a"), "x - コピー.py").readText())
    }

    @Test fun undoOfAReplacingPasteBringsTheOldFileBack() {
        write("a", "player/src/a.js", "new")
        write("b", "player/src/a.js", "old")
        val op = ops.paste(listOf(Item("a", "player")), false, "b", "", Resolution.ReplaceAll)
        ops.undo(op)
        assertEquals(listOf("player/", "player/src/", "player/src/a.js=old"), tree("b"))
        assertTrue(spaces.getValue("b").trash.entries().none { it.originalPath == "player/src/a.js" && it.item.readText() == "old" })
    }

    @Test fun undoOfATrashWhoseSpotIsTakenAgainStops() {
        write("a", "x.py", "1")
        val op = ops.trash("a", listOf("x.py"))
        write("a", "x.py", "2")
        assertFailsWith<IllegalStateException> { ops.undo(op) }
        assertEquals("2", File(code("a"), "x.py").readText())
    }
}
