package com.pythonrunner.app.files

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipFile

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
