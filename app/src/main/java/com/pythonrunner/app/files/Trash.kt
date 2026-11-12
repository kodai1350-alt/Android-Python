package com.pythonrunner.app.files

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** One item in a bot's recycle bin: where it was ([originalPath], relative to the bot), when it went, and the item itself. */
data class TrashEntry(
    val id: String,
    val originalPath: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
    val size: Long,
    val item: File,
)

/**
 * A bot's recycle bin, like Windows': deleted files and folders are moved here whole and can be put
 * back. Each entry is root/<id>/item plus root/<id>/info (where it came from and when). Nothing here
 * follows a link: a link is moved and deleted as itself.
 */
class Trash(val root: File) {
    /** Moves [source] (a file, folder or link) into the bin; [originalPath] is where it was in the bot. */
    fun put(source: File, originalPath: String, now: Long = System.currentTimeMillis()): TrashEntry {
        val entryDir = newEntryDir(now)
        val isDirectory = FileTree.isRealDirectory(source)
        val size = FileTree.sizeOf(source)
        // The info goes first, whole; an entry whose item never arrived is skipped and removed by empty().
        val info = File(entryDir, INFO)
        val tmp = File(entryDir, "$INFO.tmp")
        tmp.writeText(
            listOf("path=$originalPath", "deleted=$now", "dir=$isDirectory", "size=$size").joinToString("\n", postfix = "\n"),
        )
        if (!tmp.renameTo(info)) throw IOException("ごみ箱に入れられませんでした")
        val item = File(entryDir, ITEM)
        FileTree.moveTree(source, item)
        return TrashEntry(entryDir.name, originalPath, now, isDirectory, size, item)
    }

    /** Every entry, newest first. */
    fun entries(): List<TrashEntry> =
        root.listFiles().orEmpty().mapNotNull { read(it) }.sortedWith(compareByDescending<TrashEntry> { it.deletedAt }.thenByDescending { it.id })

    /** Moves the entry's item to [target] and drops the entry. Refuses when [target] exists. */
    fun take(entry: TrashEntry, target: File) {
        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
            throw IOException("「${target.name}」はもうあります")
        }
        target.parentFile?.mkdirs()
        FileTree.moveTree(entry.item, target)
        FileTree.deleteTree(entry.item.parentFile!!)
    }

    fun delete(entry: TrashEntry) {
        FileTree.deleteTree(File(root, entry.id))
    }

    /** ごみ箱を空にする. */
    fun empty() {
        root.listFiles().orEmpty().forEach { FileTree.deleteTree(it) }
    }

    fun size(): Long = entries().sumOf { it.size }

    private fun read(dir: File): TrashEntry? {
        val info = File(dir, INFO).takeIf { it.isFile } ?: return null
        val item = File(dir, ITEM)
        if (!item.exists() && !java.nio.file.Files.isSymbolicLink(item.toPath())) return null
        val values = info.readLines().mapNotNull { line ->
            line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
        }.toMap()
        val path = values["path"] ?: return null
        return TrashEntry(
            dir.name, path, values["deleted"]?.toLongOrNull() ?: 0, values["dir"] == "true",
            values["size"]?.toLongOrNull() ?: 0, item,
        )
    }

    private fun newEntryDir(now: Long): File {
        root.mkdirs()
        while (true) {
            val dir = File(root, "$now-${counter.incrementAndGet()}")
            if (dir.mkdir()) return dir
        }
    }

    private companion object {
        const val INFO = "info"
        const val ITEM = "item"
        val counter = AtomicInteger()
    }
}
