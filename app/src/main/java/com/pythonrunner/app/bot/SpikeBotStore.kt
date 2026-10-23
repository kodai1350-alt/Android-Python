package com.pythonrunner.app.bot

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Storage for the single bot of the trial version. */
class SpikeBotStore(
    private val baseDir: File,
    private val prefs: KeyValue,
    /** Where the bot's own files live: app-private by default, shared storage once allowed. */
    val botDir: File = File(baseDir, "bots/spike"),
) {
    val libsDir: File = File(baseDir, "bots/spike-libs")

    var mainFile: String?
        get() = prefs.get(KEY_MAIN)
        set(value) = prefs.put(KEY_MAIN, value)

    var envVars: Map<String, String>
        get() = decode(prefs.get(KEY_ENV).orEmpty())
        set(value) = prefs.put(KEY_ENV, encode(value))

    var wasRunning: Boolean
        get() = prefs.get(KEY_RUNNING) == "true"
        set(value) = prefs.put(KEY_RUNNING, value.toString())

    fun importPy(fileName: String, input: InputStream) {
        val target = File(botDir, fileName)
        require(target.canonicalFile.parentFile == botDir.canonicalFile) { "Invalid file name: $fileName" }
        botDir.mkdirs()
        target.outputStream().use { input.copyTo(it) }
        mainFile = fileName
    }

    private companion object {
        const val KEY_MAIN = "spike.mainFile"
        const val KEY_ENV = "spike.envVars"
        const val KEY_RUNNING = "spike.wasRunning"

        fun encode(vars: Map<String, String>): String =
            vars.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        fun decode(text: String): Map<String, String> =
            if (text.isEmpty()) emptyMap()
            else text.split("&").associate { pair ->
                val (k, v) = pair.split("=", limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
