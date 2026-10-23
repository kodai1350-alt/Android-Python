package com.pythonrunner.app.python

/**
 * Turns what the user pastes (often a "pip install …" line copied from the web)
 * into the package specs to install. Bare package names also work.
 */
object PipInput {
    fun packages(text: String): List<String> {
        val result = LinkedHashSet<String>()
        for (line in text.lines()) {
            for (command in line.substringBefore('#').split("&&", ";")) {
                result += parseCommand(command)
            }
        }
        return result.toList()
    }

    private fun parseCommand(command: String): List<String> {
        var tokens = command.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { it.trim('\'', '"') }
        while (tokens.firstOrNull() in PROMPT_PREFIXES) tokens = tokens.drop(1)
        val first = tokens.firstOrNull()?.trimStart('!', '%') ?: return emptyList()
        if (!PIP_COMMAND.matches(first)) return specs(tokens)
        // "pip install …" or "python -m pip install …"; any other pip subcommand is ignored.
        val installAt = tokens.indexOf("install")
        return if (installAt < 0) emptyList() else specs(tokens.drop(installAt + 1))
    }

    private fun specs(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var skipValue = false
        for (token in tokens) {
            when {
                skipValue -> skipValue = false
                token.startsWith("-") -> skipValue = '=' !in token && token in VALUE_FLAGS
                token.isNotEmpty() -> out += token
            }
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")
    private val PIP_COMMAND = Regex("pip[0-9.]*|python[0-9.]*|py")
    private val PROMPT_PREFIXES = setOf("$", ">", "sudo")

    /** pip options whose value is a separate token (it must not be taken for a package). */
    private val VALUE_FLAGS = setOf(
        "-i", "--index-url", "--extra-index-url", "-f", "--find-links", "-t", "--target",
        "-c", "--constraint", "-r", "--requirement", "-e", "--editable", "--prefix", "--root",
        "--src", "--platform", "--python-version", "--implementation", "--abi", "--trusted-host",
        "--proxy", "--cert", "--client-cert", "--cache-dir", "--log", "--progress-bar",
        "--upgrade-strategy", "--only-binary", "--no-binary", "--config-settings", "-C",
        "--retries", "--timeout",
    )
}
