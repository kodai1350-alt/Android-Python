package com.pythonrunner.app.files

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * What an import would do: every file it brings in, the existing files whose content it would
 * replace (asked about first), and its size. Paths are relative with "/" separators, sorted.
 */
data class ImportPlan(val files: List<String>, val overwrites: List<String>, val bytes: Long)

/**
 * Brings files into the bot folder in three steps: everything is first placed in a staging folder,
 * then planned (what it would overwrite, how big it is), then moved in with the old files kept
 * aside until the move has finished.
 */
object BotImporter {
    /** Folders never imported, at any depth: caches, version control and virtual environments. */
    private val SKIPPED_FOLDERS = setOf("__pycache__", ".git", "venv", ".venv")

    fun isSkipped(relativePath: String): Boolean =
        relativePath.split('/').dropLast(1).any { it in SKIPPED_FOLDERS }

    /** Total size of the ZIP's files once extracted. */
    fun zipSize(zip: File): Long = openZip(zip).use { file ->
        file.entries().asSequence().filter { !it.isDirectory }.sumOf { entry ->
            entry.size.takeIf { it >= 0 } ?: entry.compressedSize.coerceAtLeast(0)
        }
    }

    /**
     * Extracts [zip] into [staging] and returns how many files it placed. Names that would leave the
     * folder and skipped folders are dropped; a single top folder (as in GitHub's "Download ZIP") is
     * removed. [onProgress] gets 0 to 100.
     */
    fun stageZip(zip: File, staging: File, onProgress: (Int) -> Unit = {}): Int {
        staging.mkdirs()
        var count = 0
        openZip(zip).use { file ->
            val entries = file.entries().asSequence().filter { !it.isDirectory }.toList()
            val total = entries.sumOf { (it.size.takeIf { s -> s >= 0 } ?: 0L) }.coerceAtLeast(1)
            var done = 0L
            for (entry in entries) {
                val path = safePath(entry.name)
                if (path != null && !isSkipped(path)) {
                    write(staging, path) { file.getInputStream(entry) }
                    count++
                }
                done += entry.size.coerceAtLeast(0)
                onProgress((done * 100 / total).toInt().coerceIn(0, 99))
            }
        }
        liftSingleTopFolder(staging)
        onProgress(100)
        return count
    }

    /** Places one picked file at the top of [staging] (only the last part of [name] is used). */
    fun stageFile(name: String, input: InputStream, staging: File) {
        val fileName = name.replace('\\', '/').substringAfterLast('/')
        require(fileName.isNotEmpty() && fileName != "." && fileName != "..") { "Invalid file name: $name" }
        require(!name.split('/', '\\').contains("..")) { "Invalid file name: $name" }
        staging.mkdirs()
        write(staging, fileName) { input }
    }

    /**
     * Writes a picked folder's files (relative path, how to open it) into [staging] and returns how
     * many it placed; skipped folders and unsafe paths are dropped.
     */
    fun stageTree(children: List<Pair<String, () -> InputStream>>, staging: File, onProgress: (Int) -> Unit = {}): Int {
        staging.mkdirs()
        var count = 0
        children.forEachIndexed { i, (name, open) ->
            val path = safePath(name)
            if (path != null && !isSkipped(path)) {
                write(staging, path, open)
                count++
            }
            onProgress(((i + 1) * 100 / children.size.coerceAtLeast(1)).coerceIn(0, 99))
        }
        onProgress(100)
        return count
    }

    /** What importing [staging] into [botDir] would do. */
    fun plan(staging: File, botDir: File): ImportPlan {
        val files = filesIn(staging)
        val overwrites = files.filter { path ->
            val existing = File(botDir, path)
            existing.isFile && !sameContent(existing, File(staging, path))
        }
        return ImportPlan(files, overwrites, files.sumOf { File(staging, it).length() })
    }

    /** Bytes still missing to hold [needed] with a safety margin left over, or 0 when it fits. */
    fun missingBytes(needed: Long, available: Long, margin: Long = 50L * 1024 * 1024): Long =
        (needed + margin - available).coerceAtLeast(0)

    private fun filesIn(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val c = x.read()
                    if (c != y.read()) return false
                    if (c < 0) return true
                }
            }
        }
    }

    /**
     * Windows' built-in ZIP writes names in the PC's code page (Shift_JIS on Japanese PCs) without
     * marking them: read those again as MS932 when they are not valid UTF-8.
     */
    private fun openZip(zip: File): ZipFile =
        try {
            ZipFile(zip, Charsets.UTF_8).also { it.entries().asSequence().forEach { e -> e.name } }
        } catch (e: ZipException) {
            ZipFile(zip, Charset.forName("MS932"))
        } catch (e: IllegalArgumentException) {
            ZipFile(zip, Charset.forName("MS932"))
        }

    /** A relative path with "/" separators that stays inside the folder, or null. */
    private fun safePath(name: String): String? {
        val path = name.replace('\\', '/')
        if (path.startsWith("/") || path.contains(':')) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun write(base: File, path: String, open: () -> InputStream) {
        val target = File(base, path)
        require(target.canonicalPath.startsWith(base.canonicalPath + File.separator)) { "Outside the folder: $path" }
        target.parentFile?.mkdirs()
        open().use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun liftSingleTopFolder(staging: File) {
        val top = staging.listFiles().orEmpty()
        if (top.size != 1 || !top[0].isDirectory) return
        // Renamed first, so a child with the folder's own name cannot collide with it.
        val holder = File(staging, ".lift-${System.nanoTime()}")
        if (!top[0].renameTo(holder)) return
        for (child in holder.listFiles().orEmpty()) child.renameTo(File(staging, child.name))
        holder.deleteRecursively()
    }
}
