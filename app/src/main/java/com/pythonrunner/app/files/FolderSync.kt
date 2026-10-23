package com.pythonrunner.app.files

import java.io.File
import java.security.MessageDigest

/** Files that differ in the shared folder since the last sync, as paths relative to the bot folder. */
data class SyncChanges(val added: List<String>, val modified: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()
}

/**
 * Keeps a shared copy of the private bot folder that other apps and a PC can see.
 *
 * The private folder is what runs. Changes made in the shared folder are never trusted
 * automatically: [pendingImports] lists them and only [importChanges] (after the user
 * confirms) brings them in. [export] never overwrites or re-creates anything the user
 * changed or deleted in the shared folder, and secret-looking files are never shared.
 * The manifest records the content hash of every file as of the last sync.
 */
class FolderSync(
    private val privateDir: File,
    private val sharedDir: File,
    private val manifestFile: File,
) {
    fun pendingImports(): SyncChanges {
        val manifest = readManifest()
        val sharedFiles = files(sharedDir)
        val added = mutableListOf<String>()
        val modified = mutableListOf<String>()
        for ((path, file) in sharedFiles) {
            val known = manifest[path]
            when {
                known == null -> added += path
                hash(file) != known -> modified += path
            }
        }
        val removed = manifest.keys.filter { it !in sharedFiles }
        return SyncChanges(added.sorted(), modified.sorted(), removed.sorted())
    }

    fun importChanges(changes: SyncChanges) {
        val manifest = readManifest().toMutableMap()
        for (path in changes.added + changes.modified) {
            val source = File(sharedDir, path)
            if (!source.isFile) continue
            source.copyTo(File(privateDir, path), overwrite = true)
            manifest[path] = hash(source)
        }
        for (path in changes.removed) {
            File(privateDir, path).delete()
            manifest.remove(path)
        }
        writeManifest(manifest)
    }

    fun export() {
        val manifest = readManifest().toMutableMap()
        val privateFiles = files(privateDir)
        for ((path, file) in privateFiles) {
            val target = File(sharedDir, path)
            val known = manifest[path]
            when {
                // Never synced: share it unless something else already sits there.
                known == null -> if (!target.exists()) {
                    file.copyTo(target, overwrite = false)
                    manifest[path] = hash(file)
                }
                // Deleted in shared by the user: leave it deleted (it is a pending removal).
                !target.exists() -> Unit
                // Unchanged in shared since the last sync: safe to update with the app's version.
                hash(target) == known -> {
                    val current = hash(file)
                    if (current != known) {
                        file.copyTo(target, overwrite = true)
                        manifest[path] = current
                    }
                }
                // Edited in shared: keep the user's version (it is a pending import).
                else -> Unit
            }
        }
        // The bot deleted a file: drop the shared copy if nobody edited it there.
        for (path in manifest.keys.toList() - privateFiles.keys) {
            val target = File(sharedDir, path)
            if (target.isFile && hash(target) == manifest[path]) {
                target.delete()
                manifest.remove(path)
            }
        }
        writeManifest(manifest)
    }

    private fun files(dir: File): Map<String, File> =
        dir.walkTopDown()
            .onEnter { it == dir || it.name != "__pycache__" }
            .filter { it.isFile && !isSecret(it.name) }
            .associateBy { it.relativeTo(dir).invariantSeparatorsPath }

    private fun readManifest(): Map<String, String> =
        if (!manifestFile.isFile) emptyMap()
        else manifestFile.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) null else line.substring(tab + 1) to line.substring(0, tab)
        }.toMap()

    private fun writeManifest(manifest: Map<String, String>) {
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(manifest.entries.joinToString("") { (path, h) -> "$h\t$path\n" })
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Never copied to or from the shared folder. */
        fun isSecret(name: String): Boolean =
            name == ".env" || name.startsWith(".env.") || name.endsWith(".session") || name.endsWith(".session-journal")
    }
}
