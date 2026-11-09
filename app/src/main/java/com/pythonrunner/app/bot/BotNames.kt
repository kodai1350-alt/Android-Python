package com.pythonrunner.app.bot

/** The result of checking a bot name: the cleaned-up name, or why it cannot be used. */
sealed interface NameCheck {
    data class Ok(val name: String) : NameCheck
    data class Invalid(val message: String) : NameCheck
}

/**
 * Bot names double as folder names in Documents/PythonRunner, so they must be valid folder names
 * there and unique ignoring case (shared storage does not tell "MyBot" from "mybot").
 */
object BotNames {
    const val MAX_LENGTH = 40

    private const val UNSAFE = "/\\:*?\"<>|"

    /**
     * [takenNames]: the other bots' names (not the one being renamed). [blockedFolders]: folders in
     * PythonRunner that belong to no bot (not the one being adopted).
     */
    fun check(raw: String, takenNames: Collection<String>, blockedFolders: Collection<String>): NameCheck {
        val name = raw.trim()
        return when {
            name.isEmpty() -> NameCheck.Invalid("名前を入れてください")
            name.length > MAX_LENGTH -> NameCheck.Invalid("名前は${MAX_LENGTH}文字までです")
            name.startsWith(".") || name.any { isUnsafe(it) } -> NameCheck.Invalid("名前に使えない文字があります")
            takenNames.any { it.equals(name, ignoreCase = true) } -> NameCheck.Invalid("同じ名前のBotがあります")
            blockedFolders.any { it.equals(name, ignoreCase = true) } -> NameCheck.Invalid("同じ名前のフォルダがあります")
            else -> NameCheck.Ok(name)
        }
    }

    /** A free, valid name made from a picked file or folder name ("android.zip" → "android"). */
    fun suggest(pickedName: String, takenNames: Collection<String>): String {
        val base = pickedName
            .replace(Regex("\\.(zip|py)$", RegexOption.IGNORE_CASE), "")
            .map { if (isUnsafe(it)) '_' else it }.joinToString("")
            .trimStart('.').trim()
            .take(MAX_LENGTH).trim()
            .ifEmpty { "Bot" }
        fun taken(name: String) = takenNames.any { it.equals(name, ignoreCase = true) }
        if (!taken(base)) return base
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = base.take(MAX_LENGTH - suffix.length).trim() + suffix
            if (!taken(candidate)) return candidate
            n++
        }
    }

    private fun isUnsafe(c: Char) = c in UNSAFE || c < ' ' || c.code == 0x7f
}
