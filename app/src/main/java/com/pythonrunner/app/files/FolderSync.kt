package com.pythonrunner.app.files

import java.io.File
import java.security.MessageDigest

/** Files that differ in the shared folder since the last sync, as paths relative to the bot folder. */
data class SyncChanges(
    val added: List<String>,
    val modified: List<String>,
    val removed: List<String>,
    /** Content hash of each added/modified file as the user saw it; anything else is not imported. */
    val hashes: Map<String, String> = emptyMap(),
) {
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
        val hashes = HashMap<String, String>()
        for ((path, file) in sharedFiles) {
            val known = manifest[path]
            val current = hash(file)
            when {
                known == null -> added += path
                current != known -> modified += path
                else -> continue
            }
            hashes[path] = current
        }
        val removed = manifest.keys.filter { it !in sharedFiles }
        return SyncChanges(added.sorted(), modified.sorted(), removed.sorted(), hashes)
    }

    /**
     * Brings the confirmed changes into the private folder. A file is only imported if its content
     * still matches what the user confirmed; the others are returned (they stay pending).
     */
    fun importChanges(changes: SyncChanges): List<String> {
        val manifest = readManifest().toMutableMap()
        val skipped = mutableListOf<String>()
        val staging = File(manifestFile.parentFile, manifestFile.name + ".import")
        for (path in changes.added + changes.modified) {
            val source = resolveUnder(sharedDir, path)
            val target = resolveUnder(privateDir, path)
            val expected = changes.hashes[path]
            if (source == null || target == null || expected == null || !source.isFile) {
                skipped += path
                continue
            }
            // Hash the private copy itself, so a swap after confirmation cannot slip through.
            source.copyTo(staging, overwrite = true)
            if (hash(staging) != expected) {
                staging.delete()
                skipped += path
                continue
            }
            staging.copyTo(target, overwrite = true)
            staging.delete()
            manifest[path] = expected
        }
        for (path in changes.removed) {
            val target = resolveUnder(privateDir, path) ?: continue
            target.delete()
            manifest.remove(path)
        }
        writeManifest(manifest)
        return skipped
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
            .filterKeys { isSafePath(it) }

    private fun readManifest(): Map<String, String> =
        if (!manifestFile.isFile) emptyMap()
        else manifestFile.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) null else line.substring(tab + 1) to line.substring(0, tab)
        }.filter { isSafePath(it.first) }.toMap()

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

    /** [path] under [base], or null if it would escape it. */
    private fun resolveUnder(base: File, path: String): File? {
        if (!isSafePath(path)) return null
        val file = File(base, path)
        val root = base.canonicalPath + File.separator
        return file.takeIf { it.canonicalPath.startsWith(root) }
    }

    companion object {
        /** A relative path that stays inside the bot folder and fits on one manifest line. */
        fun isSafePath(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("/") && path.none { it in UNSAFE_CHARS } &&
                path.split('/').none { it.isEmpty() || it == ".." || it == "." }


        /** Never copied to or from the shared folder, where other apps can read. */
        fun isSecret(name: String): Boolean {
            val lower = name.lowercase()
            return lower == ".env" || lower.startsWith(".env.") ||
                SECRET_EXTENSIONS.any { lower.endsWith(it) } ||
                SECRET_WORDS.any { it in lower }
        }

        private val UNSAFE_CHARS = setOf('\n', '\r', '\t', '\\')

        private val SECRET_EXTENSIONS = listOf(
            ".session", ".session-journal", ".pem", ".key", ".p12", ".pfx", ".db", ".sqlite", ".sqlite3",
        )
        private val SECRET_WORDS = listOf("secret", "token", "credential", "password")
    }
}
