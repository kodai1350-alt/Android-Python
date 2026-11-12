package com.pythonrunner.app.files

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What Notepad found in a file. */
sealed interface Opened {
    /** [text] always uses "\n"; [charset], [crlf] and [bom] say how the file writes it, kept on save. */
    data class Text(
        val text: String,
        val charset: String,
        val crlf: Boolean,
        val modified: Long,
        val bom: Boolean = false,
    ) : Opened

    data class NotText(val kind: String, val size: Long) : Opened

    data class TooBig(val size: Long) : Opened
}

/** Reading and writing text files for Notepad, keeping the file's charset (UTF-8 or Shift_JIS) and line breaks. */
object TextFiles {
    const val MAX_BYTES = 1_048_576
    private const val UTF8 = "UTF-8"
    private const val SJIS = "Shift_JIS"
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun open(file: File): Opened {
        val size = file.length()
        if (size > MAX_BYTES) return Opened.TooBig(size)
        val bytes = file.readBytes()
        val notText = Opened.NotText(FileTree.kind(file.name, isDirectory = false), size)
        if (bytes.take(8192).any { it == 0.toByte() }) return notText
        val bom = bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]
        val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
        val (raw, charset) = decode(body, Charsets.UTF_8)?.let { it to UTF8 }
            ?: decode(body, BotImporter.shiftJis())?.let { it to SJIS }
            ?: return notText
        val crlf = raw.indexOf('\n').let { it > 0 && raw[it - 1] == '\r' }
        return Opened.Text(raw.replace("\r\n", "\n"), charset, crlf, file.lastModified(), bom)
    }

    /** Writes [text] the way [like] was read, to a temporary file first, then renames it into place. */
    fun save(file: File, text: String, like: Opened.Text) {
        val charset = if (like.charset == SJIS) BotImporter.shiftJis() else Charsets.UTF_8
        val lines = if (like.crlf) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
        val bytes = (if (like.bom) BOM else ByteArray(0)) + lines.toByteArray(charset)
        val tmp = File(file.parentFile, ".${file.name}.saving")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("保存できませんでした")
        }
    }

    /** Line and column (1-based) of [cursor] in [text]. */
    fun position(text: String, cursor: Int): Pair<Int, Int> {
        val before = text.substring(0, cursor.coerceIn(0, text.length))
        val line = before.count { it == '\n' } + 1
        val column = before.length - (before.lastIndexOf('\n') + 1) + 1
        return line to column
    }

    private fun decode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
