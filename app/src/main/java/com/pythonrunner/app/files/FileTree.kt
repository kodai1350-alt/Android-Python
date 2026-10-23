package com.pythonrunner.app.files

import java.io.File
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

    fun icon(name: String, isDirectory: Boolean): String {
        if (isDirectory) return "📁"
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".py") -> "🐍"
            lower == ".env" || lower.startsWith(".env.") -> "🔑"
            lower == "requirements.txt" -> "📋"
            lower.substringAfterLast('.') in CONFIG -> "⚙️"
            lower.substringAfterLast('.') in TEXT -> "📝"
            lower.substringAfterLast('.') in IMAGE -> "🖼"
            else -> "📄"
        }
    }

    private fun visibleChildren(dir: File): List<File> =
        dir.listFiles()?.filter { it.name != "__pycache__" }.orEmpty()

    private val CONFIG = setOf("json", "toml", "ini", "cfg", "yaml", "yml")
    private val TEXT = setOf("txt", "md", "log", "csv")
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp")
}
