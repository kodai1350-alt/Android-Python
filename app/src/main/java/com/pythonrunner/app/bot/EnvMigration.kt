package com.pythonrunner.app.bot

import java.io.File
import java.io.IOException

/**
 * The old 秘密の値 (kept in the app's settings) become lines of the bot's .env, once. Nothing is lost:
 * a .env already in the bot or in its shared folder is kept, and only names it lacks are added; on the
 * same name the bot's own .env wins over the shared one, and both over the old secret values.
 */
object EnvMigration {
    /** True when the bot's .env changed (the caller then copies it to the shared folder). */
    fun migrate(store: BotStore, sharedDir: File?): Boolean {
        val file = File(store.codeDir, DotEnv.FILE)
        val shared = sharedDir?.let { File(it, DotEnv.FILE) }?.takeIf { it.isFile }?.readText()
        val before = if (file.isFile) file.readText() else null
        var text = before ?: shared ?: ""
        if (before != null && shared != null) text = DotEnv.withMissing(text, DotEnv.parse(shared).values)
        text = DotEnv.withMissing(text, store.envVars)
        val changed = text != (before ?: "")
        if (changed) write(file, text)
        // Only once the values are safely in the file.
        if (store.envVars.isNotEmpty()) store.envVars = emptyMap()
        return changed
    }

    /** Written whole and then renamed into place, so a kill mid-write never leaves half a .env. */
    fun write(file: File, text: String) {
        val dir = file.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("$dir を作れませんでした")
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("$file を書けませんでした")
        }
    }
}
