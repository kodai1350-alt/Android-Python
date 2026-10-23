package com.pythonrunner.app.testutil

val isWindows: Boolean = System.getProperty("os.name").lowercase().startsWith("win")

/** Runs [script] in the host OS shell (cmd on Windows, sh elsewhere). */
fun shell(script: String): List<String> =
    if (isWindows) listOf("cmd", "/c", script) else listOf("sh", "-c", script)
