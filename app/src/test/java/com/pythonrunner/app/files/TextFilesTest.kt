package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextFilesTest {
    private val root: File = Files.createTempDirectory("text").toFile()
    private val sjis = BotImporter.shiftJis()

    @Test fun utf8LfRoundTrips() {
        val f = File(root, "a.py").apply { writeText("a\nb") }
        val opened = assertIs<Opened.Text>(TextFiles.open(f))
        assertEquals("a\nb", opened.text)
        assertEquals("UTF-8", opened.charset)
        assertFalse(opened.crlf)
        TextFiles.save(f, "a\nbc", opened)
        assertEquals("a\nbc", f.readText())
        assertFalse(f.readBytes().contains('\r'.code.toByte()))
    }

    @Test fun shiftJisCrlfIsKept() {
        val f = File(root, "s.txt").apply { writeBytes("日本語\r\nx".toByteArray(sjis)) }
        val opened = assertIs<Opened.Text>(TextFiles.open(f))
        assertEquals("日本語\nx", opened.text)
        assertEquals("Shift_JIS", opened.charset)
        assertTrue(opened.crlf)
        TextFiles.save(f, "日本語\nxy", opened)
        assertEquals("日本語\r\nxy".toByteArray(sjis).toList(), f.readBytes().toList())
    }

    @Test fun bomIsKept() {
        val f = File(root, "b.txt").apply { writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray()) }
        val opened = assertIs<Opened.Text>(TextFiles.open(f))
        assertTrue(opened.bom)
        assertEquals("x", opened.text)
        TextFiles.save(f, "y", opened)
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'y'.code.toByte()), f.readBytes().toList())
    }

    @Test fun binaryAndBigFilesAreNotOpened() {
        assertIs<Opened.NotText>(TextFiles.open(File(root, "bin").apply { writeBytes(byteArrayOf(1, 0, 2)) }))
        assertIs<Opened.TooBig>(TextFiles.open(File(root, "big").apply { writeBytes(ByteArray(1_048_577) { 'a'.code.toByte() }) }))
        assertIs<Opened.Text>(TextFiles.open(File(root, "empty").apply { writeBytes(ByteArray(0)) }))
    }

    @Test fun positionCountsLinesAndColumns() {
        assertEquals(2 to 2, TextFiles.position("ab\ncd", 4))
        assertEquals(1 to 1, TextFiles.position("", 0))
    }

    @Test fun aShiftJisSaveRefusesCharactersItCannotHold() {
        val f = File(root, "s2.txt").apply { writeBytes("あ".toByteArray(sjis)) }
        val opened = assertIs<Opened.Text>(TextFiles.open(f))
        kotlin.test.assertFailsWith<TextFiles.CannotEncode> { TextFiles.save(f, "あ😀", opened) }
        assertEquals("あ".toByteArray(sjis).toList(), f.readBytes().toList())
        TextFiles.save(f, "あ😀", opened.copy(charset = "UTF-8"))
        assertEquals("あ😀", f.readText())
    }

    @Test fun aSaveOverAFileChangedSinceItWasOpenedIsRefused() {
        val f = File(root, "c.txt").apply { writeText("a") }
        val opened = assertIs<Opened.Text>(TextFiles.open(f))
        f.writeText("changed elsewhere")
        f.setLastModified(opened.modified + 5000)
        kotlin.test.assertFailsWith<TextFiles.ChangedSinceOpened> { TextFiles.save(f, "mine", opened) }
        assertEquals("changed elsewhere", f.readText())
        TextFiles.save(f, "mine", opened, force = true)
        assertEquals("mine", f.readText())
    }

    @Test fun aLinkIsNotOpened() {
        val target = File(root, "t.txt").apply { writeText("x") }
        val link = File(root, "l.txt")
        if (runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isFailure) return
        assertIs<Opened.NotText>(TextFiles.open(link))
    }
}
