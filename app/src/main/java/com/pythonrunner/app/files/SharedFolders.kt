package com.pythonrunner.app.files

import java.io.File

/** How a bot's shared folder compares with the bot's own files, as its card shows it. */
sealed interface SharedState {
    data object InSync : SharedState
    /** Edited, added or deleted in the shared folder; waiting for 反映する. */
    data class Changed(val changes: SyncChanges) : SharedState
    /** The whole folder is gone (📁 フォルダが見つかりません). */
    data object Missing : SharedState
}

/** The bot folders in Documents/PythonRunner, seen as a whole. */
object SharedFolders {
    /** Folders that belong to no bot (compared ignoring case, as shared storage does), sorted. */
    fun unlisted(root: File, botNames: Collection<String>): List<String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }
            .filter { name -> botNames.none { it.equals(name, ignoreCase = true) } }
            .sorted()

    /**
     * Renames folder [from] to [to]. A change of case alone goes through a temporary name, because
     * shared storage sees "MyBot" and "mybot" as the same folder. False, with [from] left in place, if
     * [to] is another existing folder or a rename fails.
     */
    fun rename(root: File, from: String, to: String): Boolean {
        val source = File(root, from)
        val target = File(root, to)
        if (!source.isDirectory) return false
        if (from == to) return true
        if (!from.equals(to, ignoreCase = true)) {
            return !target.exists() && source.renameTo(target)
        }
        val holder = File(root, ".rename-${System.nanoTime()}")
        if (!source.renameTo(holder)) return false
        if (holder.renameTo(target)) return true
        holder.renameTo(source)
        return false
    }

    /** The files of [folder] a bot would take in: no caches, no secret-looking files. Sorted. */
    fun adoptableFiles(folder: File): List<String> =
        folder.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(folder).invariantSeparatorsPath }
            .filter { !BotImporter.isSkipped(it) && !FolderSync.isSecret(it.substringAfterLast('/')) }
            .sorted()
            .toList()
}
