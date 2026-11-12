package com.pythonrunner.app.files

import java.util.Locale

/**
 * Names on the files screen, the Windows way: the characters Windows refuses, names that differ only
 * in letter case count as the same (the shared folder and Windows do not tell them apart), and the
 * names Windows gives new items and copies.
 */
object FileNames {
    const val NEW_FOLDER = "新しいフォルダー"
    const val NEW_TEXT = "新しいテキスト ドキュメント.txt"
    const val NEW_PYTHON = "新しい Python ファイル.py"

    private const val BAD_CHARS = "/\\:*?\"<>|"

    /**
     * Why [name] cannot be used among [siblings] (the names already in the folder), or null when it can.
     * [renaming] is the item's current name: changing only its letter case is allowed.
     */
    fun problem(name: String, siblings: Collection<String>, renaming: String? = null): String? = when {
        name.isBlank() -> "名前を入れてください"
        name == "." || name == ".." -> "この名前は使えません"
        name.any { it in BAD_CHARS || it < ' ' } -> "名前に次の文字は使えません: / \\ : * ? \" < > |"
        siblings.any { it != renaming && sameName(it, name) } -> "同じ名前のものがあります"
        else -> null
    }

    fun sameName(a: String, b: String): Boolean = a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

    /** [wanted], or 「name (2).ext」, 「name (3).ext」… when it is taken. */
    fun unique(wanted: String, taken: Collection<String>): String {
        if (taken.none { sameName(it, wanted) }) return wanted
        val (base, ext) = split(wanted)
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (taken.none { sameName(it, candidate) }) return candidate
            n++
        }
    }

    /** The name Windows gives a copy pasted next to its original: 「name - コピー.ext」, then 「name - コピー (2).ext」. */
    fun copyName(name: String, taken: Collection<String>): String {
        val (base, ext) = split(name)
        return unique("$base - コピー$ext", taken)
    }

    fun extensionChanged(old: String, new: String): Boolean =
        !split(old).second.equals(split(new).second, ignoreCase = true)

    /** Base and extension (with its dot); a leading dot alone (.env) is part of the base. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }
}
