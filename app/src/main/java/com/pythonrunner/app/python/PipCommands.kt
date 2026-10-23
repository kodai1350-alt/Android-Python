package com.pythonrunner.app.python

import java.io.File

object PipCommands {
    /** Installs pip itself into the runtime's site-packages by running it straight from the wheel. */
    fun bootstrap(rt: PythonRuntime): List<String> =
        rt.command("${rt.pipWheel.path}/pip", "install", "--no-index", "--no-deps", rt.pipWheel.path)

    fun install(rt: PythonRuntime, packages: List<String>, target: File): List<String> =
        rt.command(
            "-m", "pip", "install", "--upgrade", "--no-input", "--disable-pip-version-check",
            "--target", target.path, *packages.toTypedArray(),
        )
}
