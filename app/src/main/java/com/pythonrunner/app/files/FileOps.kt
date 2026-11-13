package com.pythonrunner.app.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** One bot as the file operations see it: the folder that runs, and its recycle bin. */
data class BotSpace(val codeDir: File, val trash: Trash)

/** A file or folder of a bot, by its path relative to the bot's folder ("/" separators). */
data class Item(val botId: String, val path: String)

data class Move(val from: Item, val to: Item)

/** What to keep when a name is taken: the item coming in, the one already there, or both (the new one renamed). */
enum class Keep { INCOMING, EXISTING, BOTH }

/** The answer to 「ファイルの置換またはスキップ」. [PerFile] is keyed by the destination path; a path it lacks is skipped. */
sealed interface Resolution {
    data object ReplaceAll : Resolution
    data object SkipAll : Resolution
    data class PerFile(val keep: Map<String, Keep>) : Resolution
}

/** A name taken in the destination: [path] is relative to the destination bot. */
data class Clash(
    val path: String,
    val existingSize: Long,
    val existingModified: Long,
    val incomingSize: Long,
    val incomingModified: Long,
)

/** What an operation left behind, to tell later whether it is still there as it was (size -1: a folder). */
data class Stamp(val botId: String, val path: String, val size: Long, val modified: Long)

enum class NewKind { FOLDER, TEXT, PYTHON }

/** A finished operation, kept so that 元に戻す can reverse it. */
sealed interface FileOp {
    val stamps: List<Stamp>

    data class Trashed(val botId: String, val entries: List<TrashEntry>) : FileOp {
        override val stamps: List<Stamp> get() = emptyList()
    }

    /** Cut and paste, or a rename. [replaced] holds what the move pushed into each bot's bin. */
    data class Moved(
        val moves: List<Move>,
        val replaced: Map<String, List<TrashEntry>>,
        override val stamps: List<Stamp>,
    ) : FileOp

    data class Copied(
        val botId: String,
        val created: List<String>,
        val replaced: List<TrashEntry>,
        override val stamps: List<Stamp>,
    ) : FileOp

    data class Created(val botId: String, val path: String, override val stamps: List<Stamp>) : FileOp

    /** Items taken out of the bin back to [paths]. */
    data class Restored(
        val botId: String,
        val paths: List<String>,
        val replaced: List<TrashEntry>,
        override val stamps: List<Stamp>,
    ) : FileOp
}

/**
 * The files screen's operations, the Windows way: what is replaced goes to the recycle bin, names that
 * differ only in case clash, installed parts (node_modules, .venv) are never copied, and no link is ever
 * followed. An operation that fails or is cancelled midway is reversed before the error goes on.
 */
class FileOps(private val space: (String) -> BotSpace) {

    fun trash(botId: String, paths: List<String>): FileOp.Trashed {
        paths.forEach { requireNotPart(it) }
        val bin = space(botId).trash
        val entries = mutableListOf<TrashEntry>()
        try {
            for (path in paths) {
                val file = file(botId, path)
                if (!exists(file)) throw IOException("「${path.substringAfterLast('/')}」が見つかりません")
                entries += bin.put(file, path)
            }
        } catch (e: Exception) {
            runCatching { undo(FileOp.Trashed(botId, entries), check = false) }
            throw e
        }
        return FileOp.Trashed(botId, entries)
    }

    fun rename(botId: String, path: String, newName: String): FileOp.Moved {
        requireNotPart(path)
        val from = file(botId, path)
        if (!exists(from)) throw IOException("「${from.name}」が見つかりません")
        val siblings = from.parentFile!!.list().orEmpty().toList()
        FileNames.problem(newName, siblings, renaming = from.name)?.let { throw IllegalArgumentException(it) }
        val parent = parentOf(path)
        val newPath = join(parent, newName)
        val to = file(botId, newPath)
        if (from.name != newName) {
            // A change of letter case only goes through a temporary name, for file systems that ignore case.
            val holder = File(from.parentFile, ".rename-${System.nanoTime()}")
            FileTree.moveTree(from, holder)
            FileTree.moveTree(holder, to)
        }
        return FileOp.Moved(listOf(Move(Item(botId, path), Item(botId, newPath))), emptyMap(), listOf(stamp(botId, newPath)))
    }

    fun create(botId: String, dir: String, kind: NewKind): FileOp.Created {
        val parent = file(botId, dir).apply { mkdirs() }
        val wanted = when (kind) {
            NewKind.FOLDER -> FileNames.NEW_FOLDER
            NewKind.TEXT -> FileNames.NEW_TEXT
            NewKind.PYTHON -> FileNames.NEW_PYTHON
        }
        val name = FileNames.unique(wanted, parent.list().orEmpty().toList())
        val target = File(parent, name)
        val made = if (kind == NewKind.FOLDER) target.mkdir() else target.createNewFile()
        if (!made) throw IOException("「$name」を作れませんでした")
        val path = join(dir, name)
        return FileOp.Created(botId, path, listOf(stamp(botId, path)))
    }

    /**
     * The files a paste would put over others (folders with the same name are merged, so only files clash).
     * The walk follows the paste's own rules: a copy leaves out installed parts and links, a cut does not.
     * A file meeting a folder of the same name is refused, as in Windows.
     */
    fun clashes(sources: List<Item>, toBot: String, toDir: String, cut: Boolean = false): List<Clash> {
        val result = mutableListOf<Clash>()
        val destDir = file(toBot, toDir)
        for (source in sources) {
            val from = file(source.botId, source.path)
            if (!exists(from)) continue
            if (source.botId == toBot && parentOf(source.path) == toDir) continue // a copy name, or nothing to do
            collectClashes(from, destDir, toBot, toDir, cut, result)
        }
        return result.sortedBy { it.path }
    }

    private fun collectClashes(from: File, destDir: File, toBot: String, destPath: String, cut: Boolean, into: MutableList<Clash>) {
        val existing = findSame(destDir, from.name) ?: return
        val path = join(destPath, existing.name)
        val fromDir = FileTree.isRealDirectory(from)
        val existingDir = FileTree.isRealDirectory(existing)
        if (fromDir != existingDir) {
            throw IllegalArgumentException("「${existing.name}」という名前の${if (existingDir) "フォルダー" else "ファイル"}がすでにあります")
        }
        if (fromDir) {
            from.listFiles().orEmpty().filter { cut || !skipWhenCopying(it) }.forEach { collectClashes(it, existing, toBot, path, cut, into) }
        } else {
            into += Clash(path, FileTree.sizeOf(existing), existing.lastModified(), FileTree.sizeOf(from), from.lastModified())
        }
    }

    /**
     * Cut ([cut]) or copy [sources] into [toDir] of [toBot]. Clashing names follow [resolution]; what is
     * replaced goes to the destination's bin. Gives [FileOp.Moved] for a cut and [FileOp.Copied] for a copy.
     */
    fun paste(
        sources: List<Item>,
        cut: Boolean,
        toBot: String,
        toDir: String,
        resolution: Resolution,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): FileOp {
        for (source in sources) {
            requireNotPart(source.path)
            if (source.botId == toBot && (toDir == source.path || toDir.startsWith(source.path + "/"))) {
                throw IllegalArgumentException(SUBFOLDER)
            }
        }
        // A file meeting a folder of the same name stops the paste before anything changes.
        clashes(sources, toBot, toDir, cut)
        val run = PasteRun(cut, toBot, resolution, cancelled)
        try {
            sources.forEachIndexed { i, source ->
                if (cancelled()) throw CancellationException("キャンセルしました")
                run.pasteTop(source, toDir)
                onProgress(i + 1, sources.size)
            }
        } catch (e: Exception) {
            runCatching { undo(run.result(), check = false) }
            throw e
        }
        return run.result()
    }

    private inner class PasteRun(val cut: Boolean, val toBot: String, val resolution: Resolution, val cancelled: () -> Boolean) {
        val moves = mutableListOf<Move>()
        val created = mutableListOf<String>()
        val replaced = mutableListOf<TrashEntry>()
        val emptied = mutableListOf<File>()

        fun result(): FileOp {
            val stamps = (if (cut) moves.map { it.to.path } else created).map { stamp(toBot, it) }
            return if (cut) {
                FileOp.Moved(moves.toList(), if (replaced.isEmpty()) emptyMap() else mapOf(toBot to replaced.toList()), stamps)
            } else {
                FileOp.Copied(toBot, created.toList(), replaced.toList(), stamps)
            }
        }

        fun pasteTop(source: Item, toDir: String) {
            val from = file(source.botId, source.path)
            if (!exists(from)) throw IOException("「${from.name}」が見つかりません")
            val destDir = file(toBot, toDir).apply { mkdirs() }
            if (source.botId == toBot && parentOf(source.path) == toDir) {
                if (cut) return // moved onto itself: nothing to do
                val name = FileNames.copyName(from.name, destDir.list().orEmpty().toList())
                place(source, from, join(toDir, name))
                return
            }
            pasteInto(source, from, destDir, toDir)
            // After merging a cut folder, the emptied source folders go, as in Windows.
            emptied.sortedByDescending { it.path.length }.forEach { if (it.list().isNullOrEmpty()) it.delete() }
            emptied.clear()
        }

        private fun pasteInto(source: Item, from: File, destDir: File, destPath: String) {
            if (cancelled()) throw CancellationException("キャンセルしました")
            val existing = findSame(destDir, from.name)
            if (existing == null) {
                place(source, from, join(destPath, from.name))
                return
            }
            val path = join(destPath, existing.name)
            if (FileTree.isRealDirectory(from) && FileTree.isRealDirectory(existing)) {
                for (child in from.listFiles().orEmpty().sortedBy { it.name }) {
                    if (!cut && skipWhenCopying(child)) continue
                    pasteInto(Item(source.botId, join(source.path, child.name)), child, existing, path)
                }
                if (cut) emptied += from
                return
            }
            when (keepFor(path)) {
                Keep.EXISTING -> Unit
                Keep.INCOMING -> {
                    replaced += space(toBot).trash.put(existing, path)
                    // The new one keeps its own spelling (A.js over a.js).
                    place(source, from, join(destPath, from.name))
                }
                Keep.BOTH -> place(source, from, join(destPath, FileNames.unique(from.name, destDir.list().orEmpty().toList())))
            }
        }

        private fun keepFor(path: String): Keep = when (resolution) {
            Resolution.ReplaceAll -> Keep.INCOMING
            Resolution.SkipAll -> Keep.EXISTING
            is Resolution.PerFile -> resolution.keep[path] ?: Keep.EXISTING
        }

        private fun place(source: Item, from: File, destPath: String) {
            val to = file(toBot, destPath)
            if (cut) {
                FileTree.moveTree(from, to)
                moves += Move(source, Item(toBot, destPath))
            } else {
                FileTree.copyTree(from, to, skip = ::skipWhenCopying)
                // A link copies to nothing: only what was really made is recorded (and can be undone).
                if (exists(to)) created += destPath
            }
        }
    }

    /** Puts [entries] of [botId]'s bin back where they were; a taken place follows [resolution]. */
    fun restore(botId: String, entries: List<TrashEntry>, resolution: Resolution): FileOp.Restored {
        val bin = space(botId).trash
        val paths = mutableListOf<String>()
        val replaced = mutableListOf<TrashEntry>()
        for (entry in entries) {
            val parentPath = parentOf(entry.originalPath)
            val parent = file(botId, parentPath).apply { mkdirs() }
            val name = entry.originalPath.substringAfterLast('/')
            val existing = findSame(parent, name)
            val keep = when {
                existing == null -> Keep.INCOMING
                resolution is Resolution.ReplaceAll -> Keep.INCOMING
                resolution is Resolution.SkipAll -> Keep.EXISTING
                else -> (resolution as Resolution.PerFile).keep[join(parentPath, existing.name)] ?: Keep.EXISTING
            }
            val target = when (keep) {
                Keep.EXISTING -> continue
                Keep.INCOMING -> {
                    if (existing != null) replaced += bin.put(existing, join(parentPath, existing.name))
                    entry.originalPath
                }
                Keep.BOTH -> join(parentPath, FileNames.unique(name, parent.list().orEmpty().toList()))
            }
            bin.take(entry, file(botId, target))
            paths += target
        }
        return FileOp.Restored(botId, paths, replaced, paths.map { stamp(botId, it) })
    }

    /** Where [item] went through [moves] (itself or as part of a moved folder), or null if it did not move. */
    fun remap(item: Item, moves: List<Move>): Item? {
        for (move in moves) {
            if (move.from.botId != item.botId) continue
            val rest = when {
                item.path == move.from.path -> ""
                item.path.startsWith(move.from.path + "/") -> item.path.substring(move.from.path.length)
                else -> continue
            }
            return Item(move.to.botId, move.to.path + rest)
        }
        return null
    }

    /**
     * Reverses [op]. Throws (before changing anything) when what it left behind has changed since, or what
     * it put in the bin is gone. [check] off: the rollback of an operation that just failed midway.
     */
    fun undo(op: FileOp, check: Boolean = true) {
        for (s in if (check) op.stamps else emptyList()) {
            val f = file(s.botId, s.path)
            val same = exists(f) && (s.size < 0 && FileTree.isRealDirectory(f) || s.size >= 0 && f.length() == s.size && f.lastModified() == s.modified)
            if (!same) throw IllegalStateException(CHANGED)
        }
        when (op) {
            is FileOp.Trashed -> {
                val bin = space(op.botId).trash
                if (op.entries.any { exists(file(op.botId, it.originalPath)) }) throw IllegalStateException(CHANGED)
                val held = bin.entries().map { it.id }.toSet()
                if (op.entries.any { it.id !in held }) throw IllegalStateException(CHANGED)
                op.entries.reversed().forEach { bin.take(it, file(op.botId, it.originalPath)) }
            }
            is FileOp.Moved -> {
                if (op.moves.any { exists(file(it.from.botId, it.from.path)) && it.from != it.to && !sameIgnoringCase(it) }) {
                    throw IllegalStateException(CHANGED)
                }
                for (move in op.moves.reversed()) {
                    val back = file(move.from.botId, move.from.path)
                    if (sameIgnoringCase(move)) {
                        val holder = File(back.parentFile, ".rename-${System.nanoTime()}")
                        FileTree.moveTree(file(move.to.botId, move.to.path), holder)
                        FileTree.moveTree(holder, back)
                    } else {
                        FileTree.moveTree(file(move.to.botId, move.to.path), back)
                    }
                }
                op.replaced.forEach { (bot, entries) -> putBack(bot, entries) }
            }
            is FileOp.Copied -> {
                val bin = space(op.botId).trash
                op.created.reversed().forEach { bin.put(file(op.botId, it), it) }
                putBack(op.botId, op.replaced)
            }
            is FileOp.Created -> space(op.botId).trash.put(file(op.botId, op.path), op.path)
            is FileOp.Restored -> {
                val bin = space(op.botId).trash
                op.paths.forEach { bin.put(file(op.botId, it), it) }
                putBack(op.botId, op.replaced)
            }
        }
    }

    private fun sameIgnoringCase(move: Move) =
        move.from.botId == move.to.botId && move.from.path != move.to.path && FileNames.sameName(move.from.path, move.to.path)

    private fun putBack(botId: String, entries: List<TrashEntry>) {
        val bin = space(botId).trash
        // Entries the bin no longer holds (emptied meanwhile) cannot come back; the rest do.
        val held = bin.entries().map { it.id }.toSet()
        entries.filter { it.id in held }.reversed().forEach { bin.take(it, file(botId, it.originalPath)) }
    }

    private fun stamp(botId: String, path: String): Stamp {
        val f = file(botId, path)
        return if (FileTree.isRealDirectory(f)) Stamp(botId, path, -1, 0) else Stamp(botId, path, f.length(), f.lastModified())
    }

    private fun file(botId: String, path: String): File = if (path.isEmpty()) space(botId).codeDir else File(space(botId).codeDir, path)

    private fun exists(file: File) = file.exists() || Files.isSymbolicLink(file.toPath())

    private fun findSame(dir: File, name: String): File? =
        dir.listFiles().orEmpty().firstOrNull { FileNames.sameName(it.name, name) }

    private fun skipWhenCopying(file: File) = FileTree.isInstalledPart(file.name) || Files.isSymbolicLink(file.toPath())

    private fun requireNotPart(path: String) {
        if (path.split('/').any { FileTree.isInstalledPart(it) }) {
            throw IllegalArgumentException("アプリが入れた部品（node_modules・.venv）は、この操作ができません")
        }
    }

    companion object {
        const val SUBFOLDER = "対象のフォルダーは、元のフォルダーのサブフォルダーです"
        const val CHANGED = "元に戻せませんでした（ファイルが変わっています）"

        fun parentOf(path: String) = path.substringBeforeLast('/', "")
        fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
    }
}
