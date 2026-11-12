package com.pythonrunner.app.bot

/**
 * The bot's .env file: KEY=VALUE lines, read the way python-dotenv reads them, so the values the app
 * hands to the bot match what the bot itself would load. The app never logs or shows the values.
 */
object DotEnv {
    const val FILE = ".env"

    /** [values] by name (a later line wins); [badLines] are 1-based lines that could not be read. */
    data class Parsed(val values: Map<String, String>, val badLines: List<Int>)

    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

    fun parse(text: String): Parsed {
        val values = LinkedHashMap<String, String>()
        val bad = mutableListOf<Int>()
        text.removePrefix("﻿").split('\n').forEachIndexed { index, raw ->
            val line = raw.removeSuffix("\r").trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val pair = parseLine(line.removePrefix("export ").trimStart())
            if (pair == null) bad += index + 1 else values[pair.first] = pair.second
        }
        return Parsed(values, bad)
    }

    /**
     * The values a bot's process may get, and the names left out (sorted). Names that change how programs
     * are found or loaded are never taken from .env: a .env planted in the shared folder must not be able
     * to load foreign code into the bot. The app sets its own values for most of them anyway.
     */
    fun forProcess(values: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val (dropped, kept) = values.entries.partition { (name, _) ->
            val upper = name.uppercase()
            upper.startsWith("LD_") || upper.startsWith("DYLD_") || upper in BLOCKED
        }
        return kept.associate { it.key to it.value } to dropped.map { it.key }.sorted()
    }

    private val BLOCKED = setOf(
        "PATH", "HOME", "TMPDIR", "PYTHONHOME", "PYTHONPATH", "PYTHONSTARTUP", "PYTHONEXECUTABLE", "PYTHONUSERBASE",
        "PYTHONPLATLIBDIR", "NODE_OPTIONS", "NODE_PATH", "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE", "SSL_CERT_DIR",
    )

    /** The names in [text], in file order, each once. */
    fun names(text: String): List<String> {
        val names = LinkedHashSet<String>()
        for (raw in text.removePrefix("﻿").split('\n')) {
            val line = raw.removeSuffix("\r").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            parseLine(line.removePrefix("export ").trimStart())?.let { names += it.first }
        }
        return names.toList()
    }

    /** [text] with the entries of [add] whose names it lacks appended, one per line. */
    fun withMissing(text: String, add: Map<String, String>): String {
        val present = parse(text).values.keys
        val missing = add.filterKeys { it !in present }
        if (missing.isEmpty()) return text
        val sb = StringBuilder(text)
        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        for ((key, value) in missing) sb.append(key).append('=').append(quote(value)).append('\n')
        return sb.toString()
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim()
        if (!KEY.matches(key)) return null
        val rest = line.substring(eq + 1).trim()
        val value = when {
            rest.startsWith("\"") -> doubleQuoted(rest) ?: return null
            rest.startsWith("'") -> rest.indexOf('\'', 1).takeIf { it > 0 }?.let { rest.substring(1, it) } ?: return null
            else -> rest.split(Regex("\\s#"), limit = 2)[0].trim()
        }
        return key to value
    }

    /** The text between the opening quote and the first unescaped closing one, with \" and \\ undone. */
    private fun doubleQuoted(rest: String): String? {
        val sb = StringBuilder()
        var i = 1
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '\\' && i + 1 < rest.length && rest[i + 1] in "\"\\" -> { sb.append(rest[i + 1]); i += 2 }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    private fun quote(value: String): String =
        if (value.none { it.isWhitespace() || it in "#\"'=\\" }) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
