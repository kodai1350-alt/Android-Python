package com.pythonrunner.app.files

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * What an import would do: every file it brings in, the existing files whose content it would
 * replace (asked about first), and its size. Paths are relative with "/" separators, sorted.
 */
data class ImportPlan(
    val files: List<String>,
    val overwrites: List<String>,
    val bytes: Long,
    /** Files already in the bot (under the import's scope) that the import does not bring: kept unless removed. */
    val extras: List<String> = emptyList(),
)

/**
 * Brings files into the bot folder in three steps: everything is first placed in a staging folder,
 * then planned (what it would overwrite, how big it is), then moved in with the old files kept
 * aside until the move has finished.
 */
object BotImporter {
    /**
     * Folders never imported, at any depth: caches, version control, virtual environments, and the
     * resource forks macOS Finder adds to ZIPs.
     */
    // node_modules: installed by npm on the phone, thousands of files that are never the bot's own.
    val SKIPPED_FOLDERS = setOf("__pycache__", ".git", "venv", ".venv", "__MACOSX", "node_modules")
    private val SKIPPED_FILES = setOf(".DS_Store")

    fun isSkipped(relativePath: String): Boolean {
        val parts = relativePath.split('/')
        return parts.last() in SKIPPED_FILES || parts.dropLast(1).any { it in SKIPPED_FOLDERS }
    }

    /** Total size of the ZIP's files that would be imported, once extracted. */
    fun zipSize(zip: File): Long = openZip(zip).use { file ->
        file.entries().asSequence()
            .filter { !it.isDirectory && safePath(it.name)?.let { path -> !isSkipped(path) } == true }
            .sumOf { entry -> entry.size.takeIf { it >= 0 } ?: entry.compressedSize.coerceAtLeast(0) }
    }

    /**
     * Extracts [zip] into [staging] and returns how many files it placed. Names that would leave the
     * folder and skipped folders are dropped; a single top folder (as in GitHub's "Download ZIP") is
     * removed. [onProgress] gets 0 to 100.
     */
    fun stageZip(zip: File, staging: File, prefix: String = "", onProgress: (Int) -> Unit = {}): Int {
        val base = under(staging, prefix).apply { mkdirs() }
        var count = 0
        openZip(zip).use { file ->
            val entries = file.entries().asSequence().filter { !it.isDirectory }.toList()
            val total = entries.sumOf { (it.size.takeIf { s -> s >= 0 } ?: 0L) }.coerceAtLeast(1)
            var done = 0L
            for (entry in entries) {
                val path = safePath(entry.name)
                if (path != null && !isSkipped(path)) {
                    write(base, path) { file.getInputStream(entry) }
                    count++
                }
                done += entry.size.coerceAtLeast(0)
                onProgress((done * 100 / total).toInt().coerceIn(0, 99))
            }
        }
        liftSingleTopFolder(base)
        onProgress(100)
        return count
    }

    /** Places one picked file at the top of [staging] (only the last part of [name] is used). */
    fun stageFile(name: String, input: InputStream, staging: File, prefix: String = "") {
        require(!name.split('/', '\\').contains("..")) { "Invalid file name: $name" }
        // safePath also refuses control characters, which could forge lines of the rollback marker.
        val fileName = safePath(name.replace('\\', '/').substringAfterLast('/'))
            ?: throw IllegalArgumentException("Invalid file name: $name")
        val base = under(staging, prefix).apply { mkdirs() }
        write(base, fileName) { input }
    }

    /**
     * Writes a picked folder's files (relative path, how to open it) into [staging] and returns how
     * many it placed; skipped folders and unsafe paths are dropped.
     */
    fun stageTree(
        children: List<Pair<String, () -> InputStream>>,
        staging: File,
        prefix: String = "",
        onProgress: (Int) -> Unit = {},
    ): Int {
        val base = under(staging, prefix).apply { mkdirs() }
        var count = 0
        children.forEachIndexed { i, (name, open) ->
            val path = safePath(name)
            if (path != null && !isSkipped(path)) {
                write(base, path, open)
                count++
            }
            onProgress(((i + 1) * 100 / children.size.coerceAtLeast(1)).coerceIn(0, 99))
        }
        onProgress(100)
        return count
    }

    /**
     * What importing [staging] into [botDir] would do. With a [scope] ("" for the whole bot, "player" for
     * one folder) it also lists the bot's files there that the import lacks; installed parts and
     * app-only files (keys, sessions, databases) are never among them.
     */
    fun plan(staging: File, botDir: File, scope: String? = null): ImportPlan {
        val files = filesIn(staging)
        val overwrites = files.filter { path ->
            val existing = File(botDir, path)
            existing.isFile && !sameContent(existing, File(staging, path))
        }
        val extras = if (scope == null) emptyList() else extrasIn(botDir, scope, files.toSet())
        return ImportPlan(files, overwrites, files.sumOf { File(staging, it).length() }, extras)
    }

    private fun extrasIn(botDir: File, scope: String, incoming: Set<String>): List<String> {
        val top = under(botDir, scope)
        if (!top.isDirectory) return emptyList()
        return top.walkTopDown()
            // A link to a folder elsewhere is never entered: 消す must not reach files outside the bot.
            .onEnter { it == top || it.name !in SKIPPED_FOLDERS && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .map { it.relativeTo(botDir).invariantSeparatorsPath }
            .filter { it !in incoming && !isSkipped(it) && !FolderSync.isSecret(it.substringAfterLast('/')) }
            .sorted()
            .toList()
    }

    private fun under(dir: File, prefix: String): File =
        if (prefix.isEmpty()) dir else File(dir, safePath(prefix) ?: throw IOException("Invalid folder: $prefix"))

    /**
     * Moves the staged files into [botDir], overwriting files of the same name and leaving every other
     * file alone. The files it replaces are moved to [backup] first; if anything fails, the new files are
     * removed, the old ones put back, and the error rethrown. A marker in [backup] lets [recover] do the
     * same after the app was killed midway.
     */
    fun applyPlan(
        plan: ImportPlan,
        staging: File,
        botDir: File,
        backup: File,
        move: (File, File) -> Unit = ::defaultMove,
        cleanup: (File) -> Unit = { it.deleteRecursively() },
        /** Extras of [plan] to delete; they are moved aside like replaced files, so a failure brings them back. */
        remove: List<String> = emptyList(),
    ) {
        // A rollback that itself died left originals saved aside: put them back before anything else,
        // or this import's marker would replace that one and its cleanup would delete them.
        if (File(backup, MARKER).isFile) restoreFromMarker(botDir, backup)
        for (path in plan.files) {
            val target = File(botDir, path)
            if (target.isDirectory) throw IOException("「$path」は同じ名前のフォルダがあるため取り込めません")
            if (ancestors(path).any { File(botDir, it).isFile }) {
                throw IOException("「$path」は途中の名前と同じファイルがあるため取り込めません")
            }
        }
        val removing = remove.filter { it in plan.extras && File(botDir, it).isFile }
        val existing = plan.files.filter { File(botDir, it).isFile } + removing
        val added = plan.files - existing.toSet()
        backup.mkdirs()
        val marker = File(backup, MARKER)
        // Written whole and then renamed into place, so a kill mid-write never leaves a half line.
        val pending = File(backup, "$MARKER.tmp")
        pending.writeText(existing.joinToString("") { "E\t$it\n" } + added.joinToString("") { "N\t$it\n" })
        if (!pending.renameTo(marker)) throw IOException("取り込みの準備に失敗しました")
        val placed = mutableListOf<String>()
        try {
            // Originals go under files/, apart from the marker, so no bot file can overwrite it.
            for (path in existing) move(File(botDir, path), File(backup, "$FILES/$path"))
            for (path in plan.files) {
                move(File(staging, path), File(botDir, path))
                placed += path
            }
        } catch (e: Exception) {
            for (path in placed) File(botDir, path).delete()
            restoreFromMarker(botDir, backup)
            throw e
        }
        // The commit point: once the marker is gone the import stands, whatever the cleanup gets to.
        if (!marker.delete()) throw IOException("取り込みを確定できませんでした")
        cleanup(backup)
        staging.deleteRecursively()
    }

    /**
     * Puts the bot folder back as it was before an import the app did not live to finish. True if
     * there was one. The staging folder is always cleared.
     */
    fun recover(botDir: File, backup: File, staging: File): Boolean {
        staging.deleteRecursively()
        if (!File(backup, MARKER).isFile) return false
        restoreFromMarker(botDir, backup)
        return true
    }

    /** The file to run first: [current] while it exists, else main.py, bot.py, app.py, or the only top .py. */
    fun pickMain(botDir: File, current: String?): String? {
        if (current != null && File(botDir, current).isFile) return current
        MAIN_NAMES.firstOrNull { File(botDir, it).isFile }?.let { return it }
        val top = botDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".py") }
        return top.singleOrNull()?.name
    }

    /** The requirements.txt at the top of the imported files ([prefix] inside [staging]), if any. */
    fun requirements(staging: File, prefix: String = ""): String? =
        File(under(staging, prefix), "requirements.txt").takeIf { it.isFile }?.readText()

    /** Shift_JIS as Windows writes it, under whichever name this platform knows it by. */
    fun shiftJis(): Charset =
        listOf("MS932", "windows-31j", "Shift_JIS").firstNotNullOf { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        }

    private const val MARKER = ".importing"
    private const val FILES = "files"
    private val MAIN_NAMES = listOf("main.py", "bot.py", "app.py")

    /** New files ("N") are removed; files moved aside ("E") go back. Originals never moved stay put. */
    private fun restoreFromMarker(botDir: File, backup: File) {
        val marker = File(backup, MARKER)
        for (line in marker.readLines()) {
            val kind = line.substringBefore('\t')
            val path = safePath(line.substringAfter('\t', "")) ?: continue
            val target = File(botDir, path)
            val saved = File(backup, "$FILES/$path")
            when (kind) {
                "N" -> target.delete()
                "E" -> if (saved.isFile) {
                    target.delete()
                    defaultMove(saved, target)
                }
            }
        }
        backup.deleteRecursively()
    }

    private fun ancestors(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }

    private fun defaultMove(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
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
     * marking them: read those again as Shift_JIS when they are not valid UTF-8.
     */
    private fun openZip(zip: File): ZipFile {
        var utf8: ZipFile? = null
        return try {
            utf8 = ZipFile(zip, Charsets.UTF_8)
            utf8.entries().asSequence().forEach { it.name }  // names may be decoded only when read
            utf8
        } catch (e: Exception) {
            if (e !is ZipException && e !is IllegalArgumentException) throw e
            utf8?.close()
            ZipFile(zip, shiftJis())
        }
    }

    /** A relative path with "/" separators that stays inside the folder, or null. */
    fun safePath(name: String): String? {
        val path = name.replace('\\', '/')
        // Control characters would also let a name forge lines of the rollback marker.
        if (path.startsWith("/") || path.contains(':') || path.any { it < ' ' || it.code == 0x7f }) return null
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
