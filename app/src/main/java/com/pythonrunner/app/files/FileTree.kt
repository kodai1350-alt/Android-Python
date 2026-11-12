package com.pythonrunner.app.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

data class FileEntry(
    val name: String,
    /** Path from the bot folder, with "/" separators. */
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    /** Visible children of a folder; 0 for files. */
    val childCount: Int,
)

/** Lists a bot folder for the file view: folders first, then files, Python caches hidden. */
object FileTree {
    fun list(dir: File, root: File): List<FileEntry> =
        visibleChildren(dir)
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            .map { f ->
                FileEntry(
                    name = f.name,
                    relativePath = f.relativeTo(root).invariantSeparatorsPath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isDirectory) 0 else f.length(),
                    modifiedMillis = f.lastModified(),
                    childCount = if (f.isDirectory) visibleChildren(f).size else 0,
                )
            }

    /** Address-bar segments as (label, relative path) from the bot folder down to [path]. */
    fun breadcrumbs(rootName: String, path: String): List<Pair<String, String>> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf(rootName to "") + parts.indices.map { i -> parts[i] to parts.take(i + 1).joinToString("/") }
    }

    fun parentOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun formatSize(bytes: Long): String = when {
        bytes < 1000 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Only folder vs file: the kind column says the rest in words. */
    fun icon(name: String, isDirectory: Boolean): String = if (isDirectory) "📁" else "📄"

    /** The "種類" column, like the Type column of a PC file explorer. */
    fun kind(name: String, isDirectory: Boolean): String {
        if (isDirectory) return "フォルダ"
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")
        return when {
            ext == "py" -> "Python"
            lower == ".env" -> "トークンなど"
            lower in setOf(".env.example", ".env.sample", ".env.template") -> ".env の見本"
            lower.startsWith(".env.") -> "秘密の値"
            lower == "requirements.txt" -> "ライブラリ一覧"
            ext in CONFIG -> "設定"
            ext in TEXT -> "テキスト"
            ext in IMAGE -> "画像"
            else -> "ファイル"
        }
    }

    /** Files at any depth under [dir] (what deleting the folder would remove); links are counted, not entered. */
    fun fileCount(dir: File): Int {
        val path = dir.toPath()
        if (Files.isSymbolicLink(path)) return 1
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return if (dir.isFile) 1 else 0
        return dir.listFiles().orEmpty().sumOf { fileCount(it) }
    }

    /**
     * Deletes [file] and, for a folder, everything in it. A link is removed itself and never entered:
     * a bot may link to a folder elsewhere, whose files must survive. True when it is gone.
     */
    fun deleteTree(file: File): Boolean {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) return runCatching { Files.delete(path) }.isSuccess
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) file.listFiles().orEmpty().forEach { deleteTree(it) }
        return file.delete() || !file.exists()
    }

    /** A folder that is not a link. */
    fun isRealDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Bytes in [file] and, for a folder, everything in it; links are not entered (and count as 0). */
    fun sizeOf(file: File): Long {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) return 0
        if (isRealDirectory(file)) return file.listFiles().orEmpty().sumOf { sizeOf(it) }
        return if (file.isFile) file.length() else 0
    }

    /**
     * Copies [from] to [to] (which must not exist) without following links: a link is copied as a link,
     * and [skip] leaves out whatever it names (with everything inside).
     */
    fun copyTree(from: File, to: File, skip: (File) -> Boolean = { false }) {
        if (skip(from)) return
        val path = from.toPath()
        to.parentFile?.mkdirs()
        when {
            Files.isSymbolicLink(path) -> Files.copy(path, to.toPath(), LinkOption.NOFOLLOW_LINKS)
            isRealDirectory(from) -> {
                if (!to.mkdir() && !to.isDirectory) throw java.io.IOException("$to を作れませんでした")
                from.listFiles().orEmpty().forEach { copyTree(it, File(to, it.name), skip) }
            }
            else -> from.copyTo(to)
        }
    }

    /** Moves [from] to [to]: renamed when possible, else copied without following links and then deleted. */
    fun moveTree(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        copyTree(from, to)
        if (!deleteTree(from)) throw java.io.IOException("$from を移せませんでした")
    }

    /** Folders the app fills itself (npm, the .venv link): shown, never deleted by hand. */
    fun isInstalledPart(name: String): Boolean = name == "node_modules" || name == ".venv"

    private fun visibleChildren(dir: File): List<File> =
        dir.listFiles()?.filter { it.name != "__pycache__" }.orEmpty()

    private val CONFIG = setOf("json", "toml", "ini", "cfg", "yaml", "yml")
    private val TEXT = setOf("txt", "md", "log", "csv")
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp")
}
