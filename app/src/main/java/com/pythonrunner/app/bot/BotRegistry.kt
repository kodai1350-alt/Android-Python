package com.pythonrunner.app.bot

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** One bot in the list: an id that never changes, and the name the person gave it. */
data class BotEntry(val id: String, val name: String)

/**
 * The list of bots, in the order they were added. It is the source of truth for which bots exist;
 * each is saved as one "id<TAB>name" line, written whole and then renamed into place.
 */
class BotRegistry(private val file: File) {
    @Synchronized
    fun all(): List<BotEntry> =
        if (!file.isFile) emptyList()
        else file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else BotEntry(line.substring(0, tab), line.substring(tab + 1))
        }

    @Synchronized
    fun get(id: String): BotEntry? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(name: String, id: String = newId()): BotEntry {
        requireOneLine(name)
        requireOneLine(id)
        val entry = BotEntry(id, name)
        save(all() + entry)
        return entry
    }

    @Synchronized
    fun rename(id: String, name: String) {
        requireOneLine(name)
        save(all().map { if (it.id == id) it.copy(name = name) else it })
    }

    @Synchronized
    fun remove(id: String) = save(all().filter { it.id != id })

    private fun save(entries: List<BotEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(entries.joinToString("") { "${it.id}\t${it.name}\n" })
        // renameTo does not replace an existing file on every platform; File.delete + rename would leave
        // a moment with no list at all, so fall back to copying over it.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            if (!tmp.delete()) throw IOException("Could not remove $tmp")
        }
    }

    private fun requireOneLine(text: String) =
        require('\t' !in text && '\n' !in text && '\r' !in text && text.isNotEmpty()) { "Invalid value: $text" }

    companion object {
        private val random = SecureRandom()

        /** 12 lowercase hex characters; a fresh id is never the migrated bot's fixed id. */
        fun newId(): String {
            val bytes = ByteArray(6).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
