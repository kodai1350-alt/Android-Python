package com.pythonrunner.app.bot

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/** Where a bot's settings are saved (SharedPreferences in the app, a map in tests). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * One bot's private files and settings. Everything lives under bots/<id>, which never changes when
 * the bot is renamed: code/ is what runs (other apps cannot change it), libs/ holds its packages.
 */
class BotStore(val id: String, botsRoot: File, private val prefs: KeyValue) {
    val dir = File(botsRoot, id)
    val codeDir = File(dir, "code")
    val libsDir = File(dir, "libs")
    val manifestFile = File(dir, "shared.manifest")
    val stagingDir = File(dir, "import-staging")
    val backupDir = File(dir, "import-backup")

    private val keyMain = "bot.$id.mainFile"
    private val keyEnv = "bot.$id.envVars"
    private val keyRunning = "bot.$id.wasRunning"

    var mainFile: String?
        get() = prefs.get(keyMain)
        set(value) = prefs.put(keyMain, value)

    var envVars: Map<String, String>
        get() = decodeVars(prefs.get(keyEnv).orEmpty())
        set(value) = prefs.put(keyEnv, encodeVars(value))

    var wasRunning: Boolean
        get() = prefs.get(keyRunning) == "true"
        set(value) = prefs.put(keyRunning, value.toString())

    /**
     * Deletes a file or folder of the bot ([relativePath] inside [codeDir]; anything else is refused).
     * Forgets the file to run first when it was deleted.
     */
    fun deleteFile(relativePath: String): Boolean {
        val root = codeDir.canonicalFile
        val target = File(codeDir, relativePath).canonicalFile
        if (relativePath.isEmpty() || target == root || !target.path.startsWith(root.path + File.separator)) return false
        if (!target.exists() || !target.deleteRecursively()) return false
        forgetMissingMain()
        return true
    }

    /** Clears the file to run first once it no longer exists (deleted here or by an import). */
    fun forgetMissingMain() {
        val main = mainFile ?: return
        if (!File(codeDir, main).isFile) mainFile = null
    }

    /** Removes the bot's files, packages and settings (secrets included). */
    fun deleteAll() {
        dir.deleteRecursively()
        keys(id).forEach { prefs.put(it, null) }
    }

    companion object {
        /** The settings keys of bot [id]: main file, secrets, was running. */
        fun keys(id: String): List<String> = listOf("bot.$id.mainFile", "bot.$id.envVars", "bot.$id.wasRunning")
    }
}

internal fun encodeVars(vars: Map<String, String>): String =
    vars.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

internal fun decodeVars(text: String): Map<String, String> =
    if (text.isEmpty()) emptyMap()
    else text.split("&").associate { pair ->
        val (k, v) = pair.split("=", limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
